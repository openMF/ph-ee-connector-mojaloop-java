package org.mifos.connector.mojaloop.party;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.camel.trace.GetCachedTransactionIdProcessor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static org.mifos.connector.common.ams.dto.InteropIdentifierType.MSISDN;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.IS_RTP_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TENANT_ID;

@Component
public class PartyLookupRoutes extends ErrorHandlerRouteBuilder {

    @Value("${bpmn.flows.party-lookup}")
    private String partyLookupFlow;

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
        from("rest:GET:/switch/parties/{" + PARTY_ID_TYPE + "}/{" + PARTY_ID + "}")
                .log(LoggingLevel.DEBUG, "## SWITCH -> PAYER/PAYEE inbound GET parties - STEP 2")
                .process(e -> {
                    String host = e.getIn().getHeader("Host", String.class).split(":")[0];
                    String tenantId = partyProperties.getPartyByDomain(host).getTenantId();
                            zeebeProcessStarter.startZeebeWorkflow(partyLookupFlow.replace("{tenant}", tenantId),
                                    variables -> {
                                        variables.put("Date", e.getIn().getHeader("Date"));
                                        variables.put("traceparent", e.getIn().getHeader("traceparent"));
                                        variables.put(FSPIOP_SOURCE.headerName(), e.getIn().getHeader(FSPIOP_SOURCE.headerName()));
                                        variables.put(PARTY_ID_TYPE, e.getIn().getHeader(PARTY_ID_TYPE));
                                        variables.put(PARTY_ID, e.getIn().getHeader(PARTY_ID));
                                        variables.put(TENANT_ID, tenantId);
                                    });
                        }
                )
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}")
                .unmarshal().json(JsonLibrary.Jackson, PartySwitchResponseDTO.class)
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
                .toD("rest:PUT:/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.als-host}}");

        from("direct:send-parties-error-response")
                .log(LoggingLevel.DEBUG, "######## PAYEE -> SWITCH - party lookup error response - STEP 3")
                .id("send-parties-error-response")
                .process(exchange -> {
                    exchange.getIn().setBody(exchange.getProperty(ERROR_INFORMATION));
                    mojaloopUtil.setPartyHeadersResponse(exchange);
                })
                .toD("rest:PUT:/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}/error?host={{switch.als-host}}");

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
                .toD("rest:GET:/parties/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.als-host}}");
    }
}