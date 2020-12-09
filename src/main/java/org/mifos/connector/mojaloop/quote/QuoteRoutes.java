package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.common.ams.dto.QuoteFspResponseDTO;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.FspMoneyData;
import org.mifos.connector.common.mojaloop.dto.MoneyData;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.dto.TransactionType;
import org.mifos.connector.common.mojaloop.ilp.Ilp;
import org.mifos.connector.common.mojaloop.type.AmountType;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static java.math.BigDecimal.ZERO;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.LOCAL_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_SWITCH_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_SWITCH_REQUEST_AMOUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;

@Component
public class QuoteRoutes extends ErrorHandlerRouteBuilder {

    @Value("${bpmn.flows.quote}")
    private String quoteFlow;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    private AddTraceHeaderProcessor addTraceHeaderProcessor;

    @Autowired
    private PartyProperties partyProperties;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    @Autowired
    private QuoteResponseProcessor quoteResponseProcessor;

    public QuoteRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/quotes")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYEE - forward quote request - STEP 2")
                .setProperty(QUOTE_SWITCH_REQUEST, bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(exchange -> {
                            QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);
                            PartyIdInfo payee = request.getPayee().getPartyIdInfo();
                            String tenantId = partyProperties.getPartyByDfsp(payee.getFspId()).getTenantId();

                            zeebeProcessStarter.startZeebeWorkflow(quoteFlow.replace("{tenant}", tenantId),
                                    variables -> {
                                        variables.put(QUOTE_ID, request.getQuoteId());
                                        variables.put(FSPIOP_SOURCE.headerName(), payee.getFspId());
                                        variables.put(FSPIOP_DESTINATION.headerName(), request.getPayer().getPartyIdInfo().getFspId());
                                        variables.put(TRANSACTION_ID, request.getTransactionId());
                                        variables.put(QUOTE_SWITCH_REQUEST, exchange.getProperty(QUOTE_SWITCH_REQUEST));
                                        variables.put(QUOTE_SWITCH_REQUEST_AMOUNT, request.getAmount());
                                        variables.put(TENANT_ID, tenantId);

                                        ZeebeProcessStarter.camelHeadersToZeebeVariables(exchange, variables,
                                                "Date",
                                                "traceparent"
                                        );
                                    });
                        }
                )
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));

        from("rest:PUT:/switch/quotes/{" + QUOTE_ID + "}")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchResponseDTO.class)
                .to("direct:quotes-step4");

        from("direct:quotes-step4")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYER - response for quote request - STEP 4")
                .process(quoteResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("rest:PUT:/switch/quotes/{" + QUOTE_ID + "}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYER - quote error")
                .setProperty(QUOTE_FAILED, constant(true))
                .process(quoteResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("direct:send-quote-error-to-switch")
                .id("send-quote-error-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(e -> {
                    QuoteSwitchRequestDTO request = e.getIn().getBody(QuoteSwitchRequestDTO.class);
                    mojaloopUtil.setQuoteHeadersResponse(e, request);
                    e.getIn().setBody(e.getProperty(ERROR_INFORMATION));
                    e.setProperty(QUOTE_ID, request.getQuoteId());
                })
                .toD("rest:PUT:/quotes/${exchangeProperty." + QUOTE_ID + "}/error?host={{switch.quotes-host}}");

        from("direct:send-quote-to-switch")
                .id("send-quote-to-switch")
                .log(LoggingLevel.INFO, "######## PAYEE -> SWITCH - response for quote request - STEP 3")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(exchange -> {
                    QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);
                    MoneyData requestAmount = request.getAmount();
                    stripAmount(requestAmount);

                    Ilp ilp = ilpBuilder.build(request.getTransactionId(),
                            request.getQuoteId(),
                            requestAmount.getAmountDecimal(),
                            requestAmount.getCurrency(),
                            request.getPayer(),
                            request.getPayee(),
                            requestAmount.getAmountDecimal());

                    QuoteFspResponseDTO localQuoteResponse = objectMapper.readValue(exchange.getIn().getHeader(LOCAL_QUOTE_RESPONSE, String.class), QuoteFspResponseDTO.class);
                    FspMoneyData fspFee = localQuoteResponse.getFspFee();
                    FspMoneyData fspCommission = localQuoteResponse.getFspCommission();

                    // amount format: ^([0]|([1-9][0-9]{0,17}))([.][0-9]{0,3}[1-9])?$
                    BigDecimal fspFeeAmount = (fspFee != null ? fspFee.getAmount() : ZERO).stripTrailingZeros();
                    String fspFeeCurrency = fspFee != null ? fspFee.getCurrency() : requestAmount.getCurrency();
                    BigDecimal fspCommissionAmount = (fspCommission != null ? fspCommission.getAmount() : ZERO).stripTrailingZeros();
                    String fspCommissionCurrency = fspCommission != null ? fspCommission.getCurrency() : requestAmount.getCurrency();

                    QuoteSwitchResponseDTO response = new QuoteSwitchResponseDTO(
                            requestAmount,
                            new MoneyData(requestAmount.getAmountDecimal().subtract(fspFeeAmount).subtract(fspCommissionAmount).stripTrailingZeros().toPlainString(),
                                    requestAmount.getCurrency()),
                            new MoneyData(fspFeeAmount.compareTo(ZERO) == 0 ? "0" : fspFeeAmount.toPlainString(), fspFeeCurrency),
                            new MoneyData(fspCommissionAmount.compareTo(ZERO) == 0 ? "0" : fspCommissionAmount.toPlainString(), fspCommissionCurrency),
                            LocalDateTime.now().plusHours(1),
                            null,
                            ilp.getPacket(),
                            ilp.getCondition(),
                            request.getExtensionList());

                    exchange.getIn().setBody(response);
                    exchange.setProperty(QUOTE_ID, request.getQuoteId());

                    mojaloopUtil.setQuoteHeadersResponse(exchange, request);
                })
                .process(pojoToString)
                .toD("rest:PUT:/quotes/${exchangeProperty." + QUOTE_ID + "}?host={{switch.quotes-host}}");

        from("direct:send-quote")
                .id("send-quote")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - quote request - STEP 1")
                .process(exchange -> {
                    TransactionChannelRequestDTO channelRequest = objectMapper.readValue(exchange.getProperty(CHANNEL_REQUEST, String.class), TransactionChannelRequestDTO.class);

                    TransactionType transactionType = new TransactionType();
                    transactionType.setInitiator(channelRequest.getTransactionType().getInitiator());
                    transactionType.setInitiatorType(channelRequest.getTransactionType().getInitiatorType());
                    transactionType.setScenario(channelRequest.getTransactionType().getScenario());

                    PartyIdInfo payerParty = channelRequest.getPayer().getPartyIdInfo();
                    String payerFspId = partyProperties.getPartyByTenant(exchange.getProperty(TENANT_ID, String.class)).getFspId();
                    Party payer = new Party(
                            new PartyIdInfo(payerParty.getPartyIdType(),
                                    payerParty.getPartyIdentifier(),
                                    null,
                                    payerFspId),
                            null,
                            null,
                            null);

                    PartyIdInfo requestPayeePartyIdInfo = channelRequest.getPayee().getPartyIdInfo();
                    Party payee = new Party(
                            new PartyIdInfo(requestPayeePartyIdInfo.getPartyIdType(),
                                    requestPayeePartyIdInfo.getPartyIdentifier(),
                                    null,
                                    exchange.getProperty(PARTY_LOOKUP_FSP_ID, String.class)),
                            null,
                            null,
                            null);

                    MoneyData requestAmount = channelRequest.getAmount();
                    stripAmount(requestAmount);
                    QuoteSwitchRequestDTO quoteRequest = new QuoteSwitchRequestDTO(
                            exchange.getProperty(TRANSACTION_ID, String.class),
                            null, // TODO previously sent transactionRequest, use this?
                            exchange.getProperty(QUOTE_ID, String.class),
                            payee,
                            payer,
                            AmountType.RECEIVE,
                            requestAmount,
                            null,
                            transactionType,
                            null,
                            null, // TODO should be used other then extensions for comment?
                            null,
                            channelRequest.getExtensionList());
                    exchange.getIn().setBody(quoteRequest);

                    exchange.setProperty(FSPIOP_SOURCE.headerName(), payerFspId);
                    exchange.setProperty(FSPIOP_DESTINATION.headerName(), exchange.getProperty(PARTY_LOOKUP_FSP_ID));
                    mojaloopUtil.setQuoteHeadersRequest(exchange);
                })
                .process(pojoToString)
                .process(addTraceHeaderProcessor)
                .toD("rest:POST:/quotes?host={{switch.quotes-host}}");
    }

    private void stripAmount(MoneyData requestAmount) {
        requestAmount.setAmount(requestAmount.getAmountDecimal().stripTrailingZeros().toPlainString());
    }
}
