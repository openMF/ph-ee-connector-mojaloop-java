package org.mifos.connector.mojaloop.payee;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.mojaloop.dto.Party;
import org.mifos.phee.common.mojaloop.dto.PartySwitchResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.camelHeadersToZeebeVariables;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.PARTIES_ACCEPT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.PARTIES_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.QUOTES_ACCEPT_TYPE;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class PayeePartyLookupRoutes extends ErrorHandlerRouteBuilder {

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

    public PayeePartyLookupRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:GET:/switch/parties/{"+PARTY_ID_TYPE+"}/{"+PARTY_ID+"}")
                .log(LoggingLevel.WARN, "## SWITCH -> HUB inbound GET parties - STEP 2")
                .process(e ->
                        zeebeProcessStarter.startZeebeWorkflow(partyLookupFlow, null, variables -> {
                                camelHeadersToZeebeVariables(e, variables,
                                        PARTY_ID_TYPE,
                                        PARTY_ID,
                                        FSPIOP_SOURCE.headerName(),
                                        "traceparent",
                                        "Date");
                                variables.put(TENANT_ID, partyProperties.getParty(e.getIn().getHeader(PARTY_ID_TYPE, String.class),
                                        e.getIn().getHeader(PARTY_ID, String.class)).getTenantId());
                            }
                        )
                );

        from("direct:send-parties-response")
                .id("send-parties-response")
                .process(exchange -> {
                    Party party = objectMapper.readValue(exchange.getProperty(PAYEE_PARTY_RESPONSE, String.class), Party.class);

                    exchange.setProperty(PARTY_ID, party.getPartyIdInfo().getPartyIdentifier());
                    exchange.setProperty(PARTY_ID_TYPE, party.getPartyIdInfo().getPartyIdType().name());
                    exchange.getIn().setBody(new PartySwitchResponseDTO(party));
                    mojaloopUtil.setPartyHeaders(exchange);
                })
                .process(pojoToString)
                .toD("rest:PUT:/parties/${exchangeProperty."+PARTY_ID_TYPE+"}/${exchangeProperty."+PARTY_ID+"}?host={{switch.host}}");

        from("direct:send-parties-error-response")
                .id("send-parties-error-response")
                .process(exchange -> {
                    exchange.setProperty(PARTY_ID, exchange.getIn().getHeader(PARTY_ID));
                    exchange.setProperty(PARTY_ID_TYPE, exchange.getIn().getHeader(PARTY_ID_TYPE));
                    exchange.getIn().setBody(exchange.getProperty(ERROR_INFORMATION));
                    mojaloopUtil.setPartyHeaders(exchange);
                })
                .toD("rest:PUT:/parties/${exchangeProperty."+PARTY_ID_TYPE+"}/${exchangeProperty."+PARTY_ID+"}/error?host={{switch.host}}");
    }
}