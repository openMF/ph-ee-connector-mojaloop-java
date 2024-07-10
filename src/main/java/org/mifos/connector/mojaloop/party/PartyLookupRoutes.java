package org.mifos.connector.mojaloop.party;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.connector.common.mojaloop.type.IdentifierType;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.camel.trace.GetCachedTransactionIdProcessor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import static org.mifos.connector.common.ams.dto.InteropIdentifierType.MSISDN;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.*;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.*;

@Component
public class PartyLookupRoutes extends ErrorHandlerRouteBuilder {

    @Value("${bpmn.flows.party-lookup}")
    private String partyLookupFlow;

    @Value("${mojaloop.perf-mode}")
    private boolean mojaPerfMode;

    @Value("${mojaloop.perf-resp-delay}")
    private int mojaPerfRespDelay;

    @Value("${switch.als-host}")
    private String alsHost;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    private PartyProperties partyProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    @Autowired
    private AddTraceHeaderProcessor addTraceHeaderProcessor;

    @Autowired
    private GetCachedTransactionIdProcessor getCachedTransactionIdProcessor;

    @Autowired
    private PartiesResponseProcessor partiesResponseProcessor;

    public PartyLookupRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        //@formatter:off
        from("rest:GET:/switch/parties/{" + PARTY_ID_TYPE + "}/{" + PARTY_ID + "}")
                .log(LoggingLevel.DEBUG, "## SWITCH -> PAYER/PAYEE inbound GET parties - STEP 2")
                .choice()
                    .when(e -> mojaPerfMode)
                        .wireTap("direct:send-delayed-party-dummy-response")
                    .endChoice()
                    .otherwise()
                        .process(e -> {
                            String host = e.getIn().getHeader("Host", String.class).split(":")[0];
                            log.debug("HOST: {}", host);
                            log.debug("Headers: {}", e.getIn().getHeaders());
                            String payeeFsp = e.getIn().getHeader(FSPIOP_DESTINATION.headerName(), String.class);
                            log.debug("Payeefsp: {}", payeeFsp);
                            log.debug("PARTIES: {}", objectMapper.writeValueAsString(partyProperties.getParties()));
                            String tenantId = partyProperties.getPartyByDomainAndFspId(host, payeeFsp).getTenantId();
                            log.debug("PAYEE TENANT: {}", tenantId);
                                    zeebeProcessStarter.startZeebeWorkflow(partyLookupFlow.replace("{tenant}", tenantId),
                                            variables -> {
                                                variables.put(HEADER_DATE, e.getIn().getHeader(HEADER_DATE));
                                                variables.put(HEADER_TRACEPARENT, e.getIn().getHeader(HEADER_TRACEPARENT));
                                                variables.put(FSPIOP_SOURCE.headerName(), e.getIn().getHeader(FSPIOP_SOURCE.headerName()));
                                                variables.put(PAYEE_TENANT_ID, partyProperties.getPartyByDfsp(payeeFsp).getTenantId());
                                                variables.put(PARTY_ID_TYPE, e.getIn().getHeader(PARTY_ID_TYPE));
                                                variables.put(PARTY_ID, e.getIn().getHeader(PARTY_ID));
                                                variables.put(TENANT_ID, tenantId);
                                                if(e.getIn().getHeader("X-Lookup-Callback-Url")!=null) {
                                                    variables.put("X-Lookup-Callback-Url", e.getIn().getHeader("X-Lookup-Callback-Url"));
                                                }
                                                else {
                                                    variables.put("X-Lookup-Callback-Url", alsHost);
                                                }
                                            });
                                }
                        )
                    .endChoice()
                .end()
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));
        //@formatter:on

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}")
                .setProperty(CLASS_TYPE, constant(PartySwitchResponseDTO.class))
                .to("direct:body-unmarshling")
                .process(getCachedTransactionIdProcessor)
                .to("direct:parties-step4");

        from("direct:parties-step4")
                .log(LoggingLevel.DEBUG, "######## SWITCH -> PAYER - response for parties request  - STEP 4")
                .process(partiesResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYER - parties error")
                .process(getCachedTransactionIdProcessor)
                .setProperty(PARTY_LOOKUP_FAILED, constant(true))
                .process(partiesResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("direct:send-delayed-party-dummy-response")
                .delay(mojaPerfRespDelay)
                .process(e -> {
                    String host = e.getIn().getHeader("Host", String.class).split(":")[0];

                    Party party = new Party( // only return fspId from configuration
                            new PartyIdInfo(IdentifierType.valueOf(e.getIn().getHeader(PARTY_ID_TYPE, String.class)),
                                    e.getIn().getHeader(PARTY_ID, String.class),
                                    null,
                                    partyProperties.getPartyByDomainAndFspId(host, null).getFspId()),
                            null,
                            null,
                            null);
                    e.setProperty(PAYEE_PARTY_RESPONSE, objectMapper.writeValueAsString(party));
                })
                .to("direct:send-parties-response");

        from("direct:send-parties-response")
                .log(LoggingLevel.DEBUG, "######## PAYEE -> SWITCH - party lookup response - STEP 3")
                .id("send-parties-response")
                .process(exchange -> {
                    Party party = objectMapper.readValue(exchange.getProperty(PAYEE_PARTY_RESPONSE, String.class), Party.class);

                    exchange.setProperty(PARTY_ID, party.getPartyIdInfo().getPartyIdentifier());
                    exchange.setProperty(PARTY_ID_TYPE, party.getPartyIdInfo().getPartyIdType().name());
                    exchange.getIn().setBody(new PartySwitchResponseDTO(party));
                    mojaloopUtil.setPartyHeadersResponse(exchange);
                })
                .process(pojoToString)
                .log(LoggingLevel.DEBUG, "Party response from payee: ${body}")
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setProperty(ENDPOINT, simple("/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}"))
                .to("direct:external-api-call");

        from("direct:send-parties-error-response")
                .log(LoggingLevel.DEBUG, "######## PAYEE -> SWITCH - party lookup error response - STEP 3")
                .id("send-parties-error-response")
                .process(exchange -> {
                    exchange.getIn().setBody(exchange.getProperty(ERROR_INFORMATION));
                    mojaloopUtil.setPartyHeadersResponse(exchange);
                })
                .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
                .setProperty(HOST, simple("{{switch.als-host}}"))
                .setProperty(ENDPOINT, simple("/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}/error"))
                .to("direct:external-api-call");

        from("direct:send-party-lookup")
                .id("send-party-lookup")
                .log(LoggingLevel.DEBUG, "######## PAYER -> SWITCH - party lookup request - STEP 1")
                .process(e -> {
                    TransactionChannelRequestDTO channelRequest = objectMapper.readValue(e.getProperty(CHANNEL_REQUEST, String.class), TransactionChannelRequestDTO.class);
                    PartyIdInfo requestedParty = e.getProperty(IS_RTP_REQUEST, Boolean.class) ? channelRequest.getPayer().getPartyIdInfo() : channelRequest.getPayee().getPartyIdInfo();
                    e.setProperty(PARTY_ID_TYPE, requestedParty.getPartyIdType());
                    e.setProperty(PARTY_ID, requestedParty.getPartyIdentifier());
                    e.getIn().setHeader(FSPIOP_SOURCE.headerName(), partyProperties.getPartyByTenant(e.getProperty(TENANT_ID, String.class)).getFspId());

                    mojaloopUtil.setPartyHeadersRequest(e);
                })
                .process(addTraceHeaderProcessor)
                .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                .process(e -> log.info("Mojaloop headers : {}", e.getIn().getHeaders()))
                .setProperty(HOST, simple("{{switch.als-host}}"))
                .setProperty(ENDPOINT, simple("/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}"))
                .to("direct:external-api-call")
                .log(LoggingLevel.DEBUG,"Response body: ${body}");
    }
}
