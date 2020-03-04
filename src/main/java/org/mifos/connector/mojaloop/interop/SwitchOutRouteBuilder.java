package org.mifos.connector.mojaloop.interop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilp.conditions.models.pdp.Transaction;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.phee.common.mojaloop.dto.MoneyData;
import org.mifos.phee.common.mojaloop.dto.Party;
import org.mifos.phee.common.mojaloop.dto.PartyIdInfo;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.phee.common.mojaloop.dto.TransactionType;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchRequestDTO;
import org.mifos.phee.common.mojaloop.ilp.Ilp;
import org.mifos.phee.common.util.ContextUtil;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;


@Component
public class SwitchOutRouteBuilder extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    public static final String DFSP_ID_FROM = "localdev01";
    private static final String MSISDN_FROM = "27710501999"; // localdev01
    private static final String MSISDN_TO = "27710601999"; // Acefintech / localdev02
    public static final String PARTIES_CONTENT_TYPE_HEADER = "application/vnd.interoperability.parties+json;version=1.0";
    public static final String QUOTES_CONTENT_TYPE_HEADER = "application/vnd.interoperability.quotes+json;version=1.0";
    public static final String TRANSFERS_CONTENT_TYPE_HEADER = "application/vnd.interoperability.transfers+json;version=1.0";
//    private static final String MSISDN = "27710102999"; // Lion
//    private static final String MSISDN = "27710101999"; // Buffalo
//    private static final String MSISDN = "27710203999"; // Rhino
//    private static final String MSISDN = "27710305999"; // Leopard not registered
//    private static final String MSISDN = "27710306999"; // Gorilla

    @Value("${switch.quote-service}")
    private String quoteService;

    @Value("${switch.account-lookup-service}")
    private String accountLookupService;

    @Value("${switch.transfer-service}")
    private String transferService;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Processor addTraceHeaderProcessor;

    @Autowired
    private Processor pojoToString;

    public SwitchOutRouteBuilder() {
        super.configure();
    }

    @Override
    public void configure() {
        from("seda:send-party-lookup")
                .id("send-party-lookup")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - party lookup request - STEP 1")
                .process(e -> {
                    TransactionChannelRequestDTO request = objectMapper.readValue(e.getProperty(CamelProperties.TRANSACTION_REQUEST, String.class), TransactionChannelRequestDTO.class);
                    PartyIdInfo payeePartyIdInfo = request.getPayee().getPartyIdInfo();
                    e.setProperty(CamelProperties.PAYEE_PARTY_ID_TYPE, payeePartyIdInfo.getPartyIdType());
                    e.setProperty(CamelProperties.PAYEE_PARTY_IDENTIFIER, payeePartyIdInfo.getPartyIdentifier());
                    e.setProperty(CamelProperties.PAYER_FSP_ID, request.getPayer().getPartyIdInfo().getFspId());
                })
                .removeHeaders("*")
                .process(addTraceHeaderProcessor)
                .setHeader("Accept", constant(PARTIES_CONTENT_TYPE_HEADER))
                .setHeader("Content-Type", constant(PARTIES_CONTENT_TYPE_HEADER))
                .setHeader("fspiop-source", exchangeProperty(CamelProperties.PAYER_FSP_ID))
                .setHeader("Host", constant(accountLookupService))
                .toD("rest:GET:/parties/${exchangeProperty." + CamelProperties.PAYEE_PARTY_ID_TYPE
                        + "}/${exchangeProperty." + CamelProperties.PAYEE_PARTY_IDENTIFIER + "}?host={{switch.host}}");

        from("seda:send-quote")
                .id("send-quote")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - quote request - STEP 1")
                .process(exchange -> {
                    TransactionChannelRequestDTO trRequest = objectMapper.readValue(exchange.getProperty(CamelProperties.TRANSACTION_REQUEST, String.class), TransactionChannelRequestDTO.class);

                    TransactionType transactionType = new TransactionType();
                    transactionType.setInitiator(trRequest.getTransactionType().getInitiator());
                    transactionType.setInitiatorType(trRequest.getTransactionType().getInitiatorType());
                    transactionType.setScenario(trRequest.getTransactionType().getScenario());

                    PartyIdInfo requestPayerPartyIdInfo = trRequest.getPayer().getPartyIdInfo();
                    String payerFspId = requestPayerPartyIdInfo.getFspId();
                    Party payer = new Party(
                            new PartyIdInfo(requestPayerPartyIdInfo.getPartyIdType(),
                                    requestPayerPartyIdInfo.getPartyIdentifier(),
                                    null,
                                    payerFspId), // TODO !!! MUST BE CHANGED LATER
                            /* payer fspId - is currently coming from the request body but
                             *  there are multiple scenarios how to obtain this information by calling to the mojaloop parties flow twice
                             *  or provide it with a tenant - fsp mapping in the PH or calling the PH's own fineract
                             */
                            null,
                            null,
                            null);
                    exchange.setProperty(CamelProperties.PAYER_FSP_ID, payerFspId);

                    PartyIdInfo requestPayeePartyIdInfo = trRequest.getPayee().getPartyIdInfo();
                    Party payee = new Party(
                            new PartyIdInfo(requestPayeePartyIdInfo.getPartyIdType(),
                                    requestPayeePartyIdInfo.getPartyIdentifier(),
                                    null,
                                    exchange.getProperty(CamelProperties.PAYEE_FSP_ID, String.class)),
                            null,
                            null,
                            null);

                    exchange.getIn().setBody(new QuoteSwitchRequestDTO(
                            exchange.getProperty(CamelProperties.TRANSACTION_ID, String.class),
                            UUID.randomUUID().toString(),
                            payee,
                            payer,
                            trRequest.getAmountType(),
                            trRequest.getAmount(),
                            transactionType));
                })
                .process(pojoToString)
                .removeHeaders("*")
                .process(addTraceHeaderProcessor)
                .setHeader("Accept", constant(QUOTES_CONTENT_TYPE_HEADER))
                .setHeader("Content-Type", constant(QUOTES_CONTENT_TYPE_HEADER))
                .setHeader("fspiop-source", exchangeProperty(CamelProperties.PAYER_FSP_ID))
                .setHeader("fspiop-destination", exchangeProperty(CamelProperties.PAYEE_FSP_ID))
                .setHeader("Host", constant(quoteService))
                .toD("rest:POST:/quotes?host={{switch.host}}");

        from("seda:send-transfer")
                .id("send-transfer")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - transfer request - STEP 1")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchResponseDTO.class)
                .process(exchange -> {
                    QuoteSwitchResponseDTO quoteResponse = exchange.getIn().getBody(QuoteSwitchResponseDTO.class);
                    Ilp ilp = ilpBuilder.parse(quoteResponse.getIlpPacket(), quoteResponse.getCondition());

                    Transaction transaction = ilp.getTransaction();
                    TransferSwitchRequestDTO request = new TransferSwitchRequestDTO(
                            transaction.getTransactionId(),
                            transaction.getPayer().getPartyIdInfo().getFspId(),
                            transaction.getPayee().getPartyIdInfo().getFspId(),
                            new MoneyData(transaction.getAmount().getAmount(), transaction.getAmount().getCurrency()),
                            ilp.getPacket(),
                            ilp.getCondition(),
                            ContextUtil.parseDate(quoteResponse.getExpiration()).plusHours(1),
                            null);

                    exchange.getIn().setBody(request);

                    Map<String, Object> headers = new HashMap<>();
                    headers.put("Accept", TRANSFERS_CONTENT_TYPE_HEADER);
                    headers.put("Content-Type", TRANSFERS_CONTENT_TYPE_HEADER);
                    headers.put("fspiop-source", transaction.getPayer().getPartyIdInfo().getFspId());
                    headers.put("fspiop-destination", transaction.getPayee().getPartyIdInfo().getFspId());
                    headers.put("Host", transferService);
                    exchange.getIn().removeHeaders("*");
                    exchange.getIn().setHeaders(headers);
                })
                .process(pojoToString)
                .process(addTraceHeaderProcessor)
                .log(LoggingLevel.WARN, "calling POST:/transfers?host={{switch.host}}")
                .toD("rest:POST:/transfers?host={{switch.host}}");
    }
}