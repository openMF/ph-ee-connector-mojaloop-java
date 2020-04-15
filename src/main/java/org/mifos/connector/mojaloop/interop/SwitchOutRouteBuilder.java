package org.mifos.connector.mojaloop.interop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilp.conditions.models.pdp.Transaction;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.mifos.connector.mojaloop.camel.trace.QuoteTransactionCache;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.channel.dto.ChannelPartyIdInfo;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYER_FSP_ID;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.PARTIES_ACCEPT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.PARTIES_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.QUOTES_ACCEPT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.QUOTES_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.TRANSFERS_ACCEPT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.TRANSFERS_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;


@Component
public class SwitchOutRouteBuilder extends ErrorHandlerRouteBuilder {

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

    @Autowired
    private QuoteTransactionCache quoteTransactionCache;

    public SwitchOutRouteBuilder() {
        super.configure();
    }

    @Override
    public void configure() {
        from("direct:send-party-lookup")
                .id("send-party-lookup")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - party lookup request - STEP 1")
                .process(e -> {
                    TransactionChannelRequestDTO request = objectMapper.readValue(e.getProperty(CamelProperties.TRANSACTION_REQUEST, String.class), TransactionChannelRequestDTO.class);
                    PartyIdInfo payeePartyIdInfo = request.getPayee().getPartyIdInfo();
                    e.setProperty(PARTY_ID_TYPE, payeePartyIdInfo.getPartyIdType());
                    e.setProperty(PARTY_ID, payeePartyIdInfo.getPartyIdentifier());
                    e.setProperty(PAYER_FSP_ID, request.getPayer().getPartyIdInfo().getFspId());
                })
                .removeHeaders("*")
                .process(addTraceHeaderProcessor)
                .setHeader("Accept", constant(PARTIES_ACCEPT_TYPE.headerValue()))
                .setHeader("Content-Type", constant(PARTIES_CONTENT_TYPE.headerValue()))
                .setHeader(FSPIOP_SOURCE.headerName(), exchangeProperty(PAYER_FSP_ID))
                .setHeader("Host", constant(accountLookupService))
                .toD("rest:GET:/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.host}}");

        from("direct:send-quote")
                .id("send-quote")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - quote request - STEP 1")
                .process(exchange -> {
                    TransactionChannelRequestDTO trRequest = objectMapper.readValue(exchange.getProperty(CamelProperties.TRANSACTION_REQUEST, String.class), TransactionChannelRequestDTO.class);

                    TransactionType transactionType = new TransactionType();
                    transactionType.setInitiator(trRequest.getTransactionType().getInitiator());
                    transactionType.setInitiatorType(trRequest.getTransactionType().getInitiatorType());
                    transactionType.setScenario(trRequest.getTransactionType().getScenario());

                    ChannelPartyIdInfo requestPayerPartyIdInfo = trRequest.getPayer().getPartyIdInfo();
                    String payerFspId = requestPayerPartyIdInfo.getFspId();
                    Party payer = new Party(
                            new PartyIdInfo(requestPayerPartyIdInfo.getPartyIdType(),
                                    requestPayerPartyIdInfo.getPartyIdentifier(),
                                    null,
                                    payerFspId), // TODO this should be queried somehow
                            null,
                            null,
                            null);
                    exchange.setProperty(PAYER_FSP_ID, payerFspId);

                    PartyIdInfo requestPayeePartyIdInfo = trRequest.getPayee().getPartyIdInfo();
                    Party payee = new Party(
                            new PartyIdInfo(requestPayeePartyIdInfo.getPartyIdType(),
                                    requestPayeePartyIdInfo.getPartyIdentifier(),
                                    null,
                                    exchange.getProperty(CamelProperties.PAYEE_FSP_ID, String.class)),
                            null,
                            null,
                            null);

                    String quoteId = UUID.randomUUID().toString();
                    String transactionId = exchange.getProperty(CamelProperties.TRANSACTION_ID, String.class);
                    quoteTransactionCache.add(quoteId, transactionId);
                    exchange.getIn().setBody(new QuoteSwitchRequestDTO(
                            transactionId,
                            quoteId,
                            payee,
                            payer,
                            trRequest.getAmountType(),
                            trRequest.getAmount(),
                            transactionType));
                })
                .process(pojoToString)
                .removeHeaders("*")
                .process(addTraceHeaderProcessor)
                .setHeader("Accept", constant(QUOTES_ACCEPT_TYPE.headerValue()))
                .setHeader("Content-Type", constant(QUOTES_CONTENT_TYPE.headerValue()))
                .setHeader(FSPIOP_SOURCE.headerName(), exchangeProperty(PAYER_FSP_ID))
                .setHeader(FSPIOP_DESTINATION.headerName(), exchangeProperty(CamelProperties.PAYEE_FSP_ID))
                .setHeader("Host", constant(quoteService))
                .toD("rest:POST:/quotes?host={{switch.host}}");

        from("direct:send-transfer")
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
                    headers.put("Accept", TRANSFERS_ACCEPT_TYPE.headerValue());
                    headers.put("Content-Type", TRANSFERS_CONTENT_TYPE.headerValue());
                    headers.put(FSPIOP_SOURCE.headerName(), transaction.getPayer().getPartyIdInfo().getFspId());
                    headers.put(FSPIOP_DESTINATION.headerName(), transaction.getPayee().getPartyIdInfo().getFspId());
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