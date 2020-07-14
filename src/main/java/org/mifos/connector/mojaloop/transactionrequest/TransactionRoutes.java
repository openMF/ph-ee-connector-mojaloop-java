package org.mifos.connector.mojaloop.transactionrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.TransactionRequestSwitchRequestDTO;
import org.mifos.connector.common.mojaloop.dto.TransactionRequestSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.dto.TransactionType;
import org.mifos.connector.common.mojaloop.type.AuthenticationType;
import org.mifos.connector.common.mojaloop.type.InitiatorType;
import org.mifos.connector.common.mojaloop.type.Scenario;
import org.mifos.connector.common.mojaloop.type.TransactionRequestState;
import org.mifos.connector.common.mojaloop.type.TransactionRole;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.camel.trace.GetCachedTransactionIdProcessor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.AUTH_TYPE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.INITIATOR_FSP_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.IS_AUTHORISATION_REQUIRED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_REQUEST_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_STATE;

@Component
public class TransactionRoutes extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${bpmn.flows.transaction-request}")
    private String transactionRequestFlow;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PartyProperties partyProperties;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private AddTraceHeaderProcessor addTraceHeaderProcessor;

    @Autowired
    private GetCachedTransactionIdProcessor getCachedTransactionIdProcessor;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    private TransactionResponseProcessor transactionResponseProcessor;

    public TransactionRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/transactionRequests")
                .setProperty(TRANSACTION_REQUEST, bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, TransactionRequestSwitchRequestDTO.class)
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYER - incoming transactionRequest ${body.transactionRequestId} - STEP 2")
                .process(exchange -> {
                    TransactionRequestSwitchRequestDTO transactionRequest = exchange.getIn().getBody(TransactionRequestSwitchRequestDTO.class);
                            PartyIdInfo payer = transactionRequest.getPayer();
                            org.mifos.connector.mojaloop.properties.Party payerParty = partyProperties.getPartyByDfsp(payer.getFspId());
                            zeebeProcessStarter.startZeebeWorkflow(transactionRequestFlow.replace("{tenant}", payerParty.getTenantId()),
                                    variables -> {
                                        try {
                                            variables.put(TRANSACTION_ID, transactionRequest.getTransactionRequestId());
                                            variables.put(TRANSACTION_REQUEST, exchange.getProperty(TRANSACTION_REQUEST));
                                            variables.put(PARTY_LOOKUP_FSP_ID, transactionRequest.getPayee().getPartyIdInfo().getFspId());
                                            variables.put(IS_AUTHORISATION_REQUIRED, transactionRequest.getAuthenticationType() != null);
                                            variables.put(INITIATOR_FSP_ID, payerParty.getFspId());
                                            variables.put(TENANT_ID, payerParty.getTenantId());

                                            TransactionChannelRequestDTO channelRequest = new TransactionChannelRequestDTO();
                                            channelRequest.setPayer(new Party(payer));
                                            channelRequest.setPayee(transactionRequest.getPayee());
                                            TransactionType transactionType = new TransactionType();
                                            transactionType.setInitiator(TransactionRole.PAYER);
                                            transactionType.setScenario(Scenario.PAYMENT);
                                            transactionType.setInitiatorType(InitiatorType.CONSUMER);
                                            channelRequest.setTransactionType(transactionType);
                                            channelRequest.setAmount(transactionRequest.getAmount());
                                            variables.put(CHANNEL_REQUEST, objectMapper.writeValueAsString(channelRequest));
                                            variables.put(TRANSACTION_STATE, TransactionRequestState.RECEIVED.name());

                                            ZeebeProcessStarter.camelHeadersToZeebeVariables(exchange, variables,
                                                    "Date",
                                                    "traceparent"
                                            );
                                        } catch (Exception e) {
                                            logger.error("Error when creating channelRequest for payer local quote!", e);
                                        }
                                    });
                        }
                )
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));

        from("rest:PUT:/switch/transactionRequests/{" + TRANSACTION_ID + "}")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYEE - response for transactionRequest ${header." + TRANSACTION_ID + "} - STEP 4")
                .unmarshal().json(JsonLibrary.Jackson, TransactionRequestSwitchResponseDTO.class)
                .process(transactionResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("rest:PUT:/switch/transactionRequests/{" + TRANSACTION_ID + "}/error")
                .log(LoggingLevel.INFO, "######## SWITCH error with transactionRequest ${header." + TRANSACTION_ID + "}")
                .setProperty(TRANSACTION_REQUEST_FAILED, constant(true))
                .process(transactionResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("direct:send-transaction-request")
                .id("send-transaction-request")
                .log(LoggingLevel.INFO, "######## PAYEE -> SWITCH - transactionRequest request ${exchangeProperty." + TRANSACTION_ID + "} - STEP 1")
                .process(e -> {
                    TransactionChannelRequestDTO channelRequest = objectMapper.readValue(e.getProperty(CHANNEL_REQUEST, String.class), TransactionChannelRequestDTO.class);
                    PartyIdInfo payeeParty = channelRequest.getPayee().getPartyIdInfo();
                    String payeeFspId = partyProperties.getPartyByTenant(e.getProperty(TENANT_ID, String.class)).getFspId();
                    payeeParty.setFspId(payeeFspId);
                    e.setProperty(FSPIOP_SOURCE.headerName(), payeeFspId);

                    PartyIdInfo payerParty = channelRequest.getPayer().getPartyIdInfo();
                    String payerFspId = e.getProperty(PARTY_LOOKUP_FSP_ID, String.class);
                    payerParty.setFspId(payerFspId);
                    e.setProperty(FSPIOP_DESTINATION.headerName(), payerFspId);

                    TransactionRequestSwitchRequestDTO tr = new TransactionRequestSwitchRequestDTO();
                    tr.setTransactionRequestId(e.getProperty(TRANSACTION_ID, String.class));
                    tr.setPayee(new Party(payeeParty, null, null, null));
                    tr.setPayer(payerParty);
                    tr.setAmount(channelRequest.getAmount());
                    tr.setTransactionType(channelRequest.getTransactionType());
                    tr.setExtensionList(channelRequest.getExtensionList());
                    String authType = e.getProperty(AUTH_TYPE, String.class);
                    if (!"NONE".equals(authType)) {
                        tr.setAuthenticationType(AuthenticationType.valueOf(authType));
                    }

                    e.getIn().setBody(tr);

                    mojaloopUtil.setTransactionRequestHeadersRequest(e);
                })
                .process(pojoToString)
                .process(addTraceHeaderProcessor)
                .toD("rest:POST:/transactionRequests?host={{switch.transactions-host}}");

        from("direct:send-transaction-state")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - transactionState response ${exchangeProperty." + TRANSACTION_ID + "} - STEP 3")
                .process(e -> {
                    TransactionRequestSwitchResponseDTO response = new TransactionRequestSwitchResponseDTO();
                    response.setTransactionId(e.getProperty(TRANSACTION_ID, String.class));
                    response.setTransactionRequestState(TransactionRequestState.valueOf(e.getProperty(TRANSACTION_STATE, String.class)));

                    e.getIn().setBody(response);
                    mojaloopUtil.setTransactionRequestHeadersResponse(e);
                })
                .process(pojoToString)
                .toD("rest:PUT:/transactionRequests/${exchangeProperty." + TRANSACTION_ID + "}?host={{switch.transactions-host}}");

        //  --- Authorizations endpoints ---
        from("direct:send-payer-authorisation")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - authorizations request - STEP 1")
                .process(e -> {
                })
                .toD("rest:GET:/authorizations/${exchangeProperty." + TRANSACTION_ID + "}?host={{switch.transactions-host}}");

        from("rest:GET:/switch/authorizations/{" + TRANSACTION_ID + "}")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYEE - authorizations request - STEP 2")
                .process(e -> {
                });

        from("direct:send-payee-authorisation-response")
                .log(LoggingLevel.INFO, "######## PAYEE -> SWITCH - authorizations request - STEP 3")
                .process(e -> {
                })
                .toD("rest:PUT:/authorizations/${exchangeProperty." + TRANSACTION_ID + "}?host={{switch.transactions-host}}");

        from("rest:PUT:/switch/authorizations/{" + TRANSACTION_ID + "}")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYER - response for authorizations - STEP 4")
                .process(e -> {
                })
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("rest:PUT:/switch/authorizations/{" + TRANSACTION_ID + "}/error")
                .log(LoggingLevel.INFO, "######## SWITCH error with authorizations")
                .process(e -> {
                })
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));
    }
}
