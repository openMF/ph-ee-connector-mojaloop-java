package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.mifos.connector.common.ams.dto.QuoteFspResponseDTO;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Extension;
import org.mifos.connector.common.mojaloop.dto.ExtensionList;
import org.mifos.connector.common.mojaloop.dto.FspMoneyData;
import org.mifos.connector.common.mojaloop.dto.MoneyData;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.dto.TransactionType;
import org.mifos.connector.common.mojaloop.type.AmountType;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.ilp.Ilp;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.model.QuoteCallbackDTO;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static java.math.BigDecimal.ZERO;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.*;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.*;

@Component
public class QuoteRoutes extends ErrorHandlerRouteBuilder {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${mojaloop.perf-mode}")
    private boolean mojaPerfMode;

    @Value("${mojaloop.perf-resp-delay}")
    private int mojaPerfRespDelay;

    @Value("${bpmn.flows.quote}")
    private String quoteFlow;

    @Value("${switch.quotes-host}")
    private String quoteHost;

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
                .choice() // @formatter:off
                    .when(e -> mojaPerfMode)
                        .wireTap("direct:send-delayed-quote-dummy-response")
                    .endChoice()
                .otherwise()
                    //.unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                    .setProperty(CLASS_TYPE, constant(QuoteSwitchRequestDTO.class))
                    .to("direct:body-unmarshling")
                    .process(exchange -> { // @formatter:on
                                QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);
                                PartyIdInfo payee = request.getPayee().getPartyIdInfo();
                                String tenantId = partyProperties.getPartyByDfsp(payee.getFspId()).getTenantId();
                                logger.info("TOMD-post-/switchquotes"); 

                                zeebeProcessStarter.startZeebeWorkflow(quoteFlow.replace("{tenant}", tenantId),
                                        variables -> {
                                            variables.put("initiator", request.getTransactionType().getInitiator());
                                            variables.put("initiatorType", request.getTransactionType().getInitiatorType());
                                            variables.put("scenario", request.getTransactionType().getScenario());
                                            variables.put("amount", new FspMoneyData(request.getAmount().getAmountDecimal(), request.getAmount().getCurrency()));
                                            variables.put("transactionId", request.getTransactionId());
                                            variables.put("transferCode", request.getTransactionRequestId());    // TODO is that right?

                                            ExtensionList extensionList = request.getExtensionList();
                                            String note = extensionList == null ? "" : extensionList.getExtension().stream()
                                                    .filter(e -> "comment".equals(e.getKey()))
                                                    .findFirst()
                                                    .map(Extension::getValue)
                                                    .orElse("");
                                            variables.put("note", note);

                                            variables.put(QUOTE_ID, request.getQuoteId());
                                            variables.put(FSPIOP_SOURCE.headerName(), payee.getFspId());
                                            variables.put(FSPIOP_DESTINATION.headerName(), request.getPayer().getPartyIdInfo().getFspId());
                                            variables.put(TRANSACTION_ID, request.getTransactionId());
                                            variables.put(QUOTE_SWITCH_REQUEST, exchange.getProperty(QUOTE_SWITCH_REQUEST));
                                            variables.put(QUOTE_SWITCH_REQUEST_AMOUNT, request.getAmount());
                                            variables.put(TENANT_ID, tenantId);
                                            if(exchange.getIn().getHeader("X-Quote-Callback-Url")!=null) {
                                                variables.put("X-Quote-Callback-Url", exchange.getIn().getHeader("X-Quote-Callback-Url"));
                                            }
                                            else {
                                                variables.put("X-Quote-Callback-Url", quoteHost);
                                            }

                                            ZeebeProcessStarter.camelHeadersToZeebeVariables(exchange, variables,
                                                    HEADER_DATE,
                                                    HEADER_TRACEPARENT
                                            );
                                        });
                            }
                    )
                .endChoice()
                .end()
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));

        from("direct:send-delayed-quote-dummy-response")
                .delay(mojaPerfRespDelay)
                .process(exchange -> {
                    String currency = "TZS";
                    FspMoneyData fspFee = new FspMoneyData(ZERO, currency);
                    FspMoneyData fspCommission = new FspMoneyData(ZERO, currency);

                    QuoteFspResponseDTO response = new QuoteFspResponseDTO();
                    response.setFspFee(fspFee);
                    response.setFspCommission(fspCommission);

                    exchange.getIn().setHeader(LOCAL_QUOTE_RESPONSE, objectMapper.writeValueAsString(response));
                })
                .to("direct:send-quote-to-switch");


        from("rest:PUT:/switch/quotes/{" + QUOTE_ID + "}")
                .log(LoggingLevel.INFO, "TOMD ######## put /switch/quotes/${header.QUOTE_ID} - quote callback")
                .setProperty(CLASS_TYPE, constant(QuoteCallbackDTO.class))
                .to("direct:body-unmarshling")
                .process(exchange -> logger.debug("Received callback: {}", objectMapper.writeValueAsString(exchange.getIn().getBody(QuoteCallbackDTO.class))))
                .to("direct:quotes-step4");

        from("direct:quotes-step4")
                .log(LoggingLevel.INFO, "TOMD ######## SWITCH -> PAYER - response for quote request - STEP 4")
                .process(quoteResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("rest:PUT:/switch/quotes/{" + QUOTE_ID + "}/error")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYER - quote error")
                .log(LoggingLevel.DEBUG,"Body: ${body}")
                .setProperty(QUOTE_FAILED, constant(true))
                .process(quoteResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("direct:send-quote-error-to-switch")
                .id("send-quote-error-to-switch")
                //.unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .setProperty(CLASS_TYPE, constant(QuoteSwitchRequestDTO.class))
                .to("direct:body-unmarshling")
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
                //.unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .setProperty(CLASS_TYPE, constant(QuoteSwitchRequestDTO.class))
                .to("direct:body-unmarshling")
                .process(exchange -> {
                    logger.info("TOMD ######## PAYEE -> SWITCH - response for quote request - STEP 3");
                    QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);
                    MoneyData requestAmount = request.getAmount();
                    stripAmount(requestAmount);

                    Ilp ilp = ilpBuilder.build(request.getTransactionId(),
                            request.getQuoteId(),
                            requestAmount.getAmountDecimal(),
                            requestAmount.getCurrency(),
                            objectMapper.readValue(objectMapper.writeValueAsString(request.getPayer()),
                                    org.mifos.connector.mojaloop.ilp.Party.class),
                            objectMapper.readValue(objectMapper.writeValueAsString(request.getPayee()),
                                    org.mifos.connector.mojaloop.ilp.Party.class),
                            requestAmount.getAmountDecimal());

                    String localQuoteResponseString = exchange.getIn().getHeader(LOCAL_QUOTE_RESPONSE, String.class);
                    logger.info("TOMD ## parsing local quote response string: {}", localQuoteResponseString);
                    logger.info("TOMD-QUOTE1 ILP object: {}", objectMapper.writeValueAsString(ilp));
                    QuoteFspResponseDTO localQuoteResponse = objectMapper.readValue(localQuoteResponseString, QuoteFspResponseDTO.class);
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
                            // TOMD TODO : correct the maths here to get the correct amount but for now I just hardcode payeeFspFee else there are
                            //      errors from vNext quotes i.e it returns 400 
                            // new MoneyData(fspFeeAmount.compareTo(ZERO) == 0 ? "0" : fspFeeAmount.toPlainString(), fspFeeCurrency),
                            new MoneyData(new BigDecimal("0.3"), fspFeeCurrency),
                            //new MoneyData(fspCommissionAmount.compareTo(ZERO) == 0 ? "0" : fspCommissionAmount.toPlainString(), fspCommissionCurrency),
                            new MoneyData(new BigDecimal("0.4"), fspCommissionCurrency),
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
                .log(LoggingLevel.INFO, "Quote response from payee: ${body}")
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setProperty(ENDPOINT, simple("/quotes/${exchangeProperty." + QUOTE_ID + "}"))
                .to("direct:external-api-call");

        from("direct:send-quote")
                .id("send-quote")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - quote request - STEP 1")
                .process(exchange -> {
                    TransactionChannelRequestDTO channelRequest = objectMapper.readValue(exchange.getProperty(CHANNEL_REQUEST, String.class), TransactionChannelRequestDTO.class);
                    logger.info("TOMD Channel request: {}", channelRequest);
                    TransactionType transactionType = new TransactionType();
                    transactionType.setInitiator(channelRequest.getTransactionType().getInitiator());
                    transactionType.setInitiatorType(channelRequest.getTransactionType().getInitiatorType());
                    transactionType.setScenario(channelRequest.getTransactionType().getScenario());

                    PartyIdInfo payerParty = channelRequest.getPayer().getPartyIdInfo();
                    // TOMD: The payer and payeeFSpId should come from the channelRequest object as they appear to be reliably sent 
                    //       this issue is that this needs a change to the connector-common TransactionChannelRequestDTO object
                    //       to support this.  Making this modifiucation would reduce the need for seperate party properties 
                    //       config in the connector-mojaloop project.
                //     logger.info("TOMD Quotes parties");
                //     partyProperties.listAllParties().forEach(party -> {
                //         log.info("Party / tenantId : {}", party.getTenantId());
                //         log.info("Party / fspId : {}", party.getFspId());
                //         log.info("Domain/ fspId : {}", party.getDomain());
                //     });
                //     logger.info("TOMD Quote Payer party: {}", payerParty);
                    String payerFspId = partyProperties.getPartyByTenant(exchange.getProperty(TENANT_ID, String.class)).getFspId();
                    logger.info("TOMD Quote Payer party should be greenbank : {}", payerParty);
                    PartyIdInfo requestPayeePartyIdInfo = channelRequest.getPayee().getPartyIdInfo();
                    // TOMD: TODO  the payeeFspID is null in the channel request object
                    //       need to debug and remove this hardcoding   
                    //String payeeFspId = partyProperties.getPartyByTenant(requestPayeePartyIdInfo.getFspId()).getFspId();
                    String payeeFspId = "bluebank";
                    Party payer = new Party(
                            new PartyIdInfo(payerParty.getPartyIdType(),
                                    payerParty.getPartyIdentifier(),
                                    null,
                                    payerFspId),
                            null,
                            null,
                            null);


                    Party payee = new Party(
                            new PartyIdInfo(requestPayeePartyIdInfo.getPartyIdType(),
                                    requestPayeePartyIdInfo.getPartyIdentifier(),
                                    null,
                                    payeeFspId),
                            null,
                            null,
                            null);

                    MoneyData requestAmount = channelRequest.getAmount();
                    logger.info("TOMD in step 1 : Amount decimal: {}", channelRequest.getAmount().getAmountDecimal());
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

                    // TOMD TODO: this is a hack to get the quote request to work
                    //exchange.setProperty(FSPIOP_SOURCE.headerName(), payerFspId);
                    exchange.setProperty(FSPIOP_SOURCE.headerName(), "greenbank");
                    exchange.setProperty(FSPIOP_DESTINATION.headerName(), "bluebank");
                    //exchange.setProperty(FSPIOP_DESTINATION.headerName(), exchange.getProperty(PARTY_LOOKUP_FSP_ID));
                    mojaloopUtil.setQuoteHeadersRequest(exchange);
                    if (simple("{{switch.quotes-host}}") == null ) {
                        logger.info("TOMD Quote host is null ..exiting ");
                        System.exit(HIGHEST);
                    } else { 
                        logger.info("TOMD Quote host is {} ", simple("{{switch.quotes-host}}"));
                    }
                
                })
                .process(pojoToString)
                .process(addTraceHeaderProcessor)
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                // TOMDO TODO : hardcoded for now needs to come from properties config 
                .setProperty(HOST, constant("http://fspiop-api-svc.vnext.svc.cluster.local:4000"))
                .setProperty(ENDPOINT, constant("/quotes"))
                .to("direct:external-api-call");
    }

    private void stripAmount(MoneyData requestAmount) {
        requestAmount.setAmount(requestAmount.getAmountDecimal().stripTrailingZeros().toPlainString());
    }
}
