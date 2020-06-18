package org.mifos.connector.mojaloop.party;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.json.JSONArray;
import org.json.JSONObject;
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

import java.util.Map;

import static org.mifos.connector.common.ams.dto.InteropIdentifierType.MSISDN;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.IS_RTP_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.PARTY_LOOKUP_FAILED;

@Component
public class PartyLookupRoutes extends ErrorHandlerRouteBuilder {

    private static final String ORIGINAL_HEADERS_PROPERTY = "originalHeaders";

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
                    e.setProperty(ORIGINAL_HEADERS_PROPERTY, e.getIn().getHeaders());
                })
                .to("direct:get-dfsp-from-oracle")
                .process(e -> {
                            JSONObject oracleResponse = new JSONObject(e.getIn().getBody(String.class));
                            JSONArray partyList = oracleResponse.getJSONArray("partyList");
                            if (partyList.length() != 1) {
                                throw new RuntimeException("Can not identify dfsp from oracle with type: " + e.getIn().getHeader(PARTY_ID_TYPE, String.class) + " and value: "
                                        + e.getIn().getHeader(PARTY_ID, String.class) + ", response contains " + partyList.length() + " elements!");
                            }
                            String tenantId = partyProperties.getParty(partyList.getJSONObject(0).getString("fspId")).getTenantId();
                            Map<String, Object> originalHeaders = (Map<String, Object>) e.getProperty(ORIGINAL_HEADERS_PROPERTY);

                            zeebeProcessStarter.startZeebeWorkflow(partyLookupFlow.replace("{tenant}", tenantId),
                                    variables -> {
                                        variables.put("Date", originalHeaders.get("Date"));
                                        variables.put("traceparent", originalHeaders.get("traceparent"));
                                        variables.put(PARTY_ID_TYPE, originalHeaders.get(PARTY_ID_TYPE));
                                        variables.put(PARTY_ID, originalHeaders.get(PARTY_ID));
                                        variables.put(TENANT_ID, tenantId);
                                    });
                        }
                );

        from("direct:get-dfsp-from-oracle")
                .id("get-dfsp-from-oracle")
                .process(e -> {
                    e.getIn().getHeaders().remove("CamelHttpPath");
                    e.getIn().getHeaders().remove("CamelHttpUri");
                })
                .toD("rest:GET:/oracle/participants/${header." + PARTY_ID_TYPE + "}/${header." + PARTY_ID + "}?host={{switch.oracle-host}}");

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}")
                .log(LoggingLevel.DEBUG, "######## SWITCH -> PAYER - response for parties request  - STEP 3")
                .unmarshal().json(JsonLibrary.Jackson, PartySwitchResponseDTO.class)
                .process(getCachedTransactionIdProcessor)
                .process(partiesResponseProcessor);

        from("rest:PUT:/switch/parties/" + MSISDN + "/{partyId}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYER - parties error")
                .process(getCachedTransactionIdProcessor)
                .setProperty(PARTY_LOOKUP_FAILED, constant(true))
                .process(partiesResponseProcessor);

        from("direct:send-parties-response")
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
                .id("send-parties-error-response")
                .process(exchange -> {
                    exchange.setProperty(PARTY_ID, exchange.getIn().getHeader(PARTY_ID));
                    exchange.setProperty(PARTY_ID_TYPE, exchange.getIn().getHeader(PARTY_ID_TYPE));
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