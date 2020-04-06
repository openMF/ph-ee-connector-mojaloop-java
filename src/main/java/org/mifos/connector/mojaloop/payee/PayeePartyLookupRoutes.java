package org.mifos.connector.mojaloop.payee;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.mojaloop.dto.ErrorInformation;
import org.mifos.phee.common.mojaloop.dto.Party;
import org.mifos.phee.common.mojaloop.dto.PartyIdInfo;
import org.mifos.phee.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.phee.common.mojaloop.type.IdentifierType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.camelHeadersToZeebeVariables;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.PARTIES_CONTENT_TYPE;

@Component
public class PayeePartyLookupRoutes extends ErrorHandlerRouteBuilder {

    @Value("${switch.account-lookup-service}")
    private String accountLookupService;

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

        from("direct:send-parties-callback-response")
                .id("send-parties-callback-response")
                .process(exchange -> {
                    Party party = objectMapper.readValue(exchange.getProperty(PAYEE_PARTY_RESPONSE, String.class), Party.class);

                    exchange.setProperty(PARTY_ID, party.getPartyIdInfo().getPartyIdentifier());
                    exchange.setProperty(PARTY_ID_TYPE, party.getPartyIdInfo().getPartyIdType().name());
                    exchange.getIn().setBody(new PartySwitchResponseDTO(party));
                })
                .to("direct:send-parties-callback");

        from("direct:send-parties-callback-error")
                .id("send-parties-callback-error")
                .process(exchange -> {
                    exchange.setProperty(PARTY_ID, exchange.getIn().getHeader(PARTY_ID));
                    exchange.setProperty(PARTY_ID_TYPE, exchange.getIn().getHeader(PARTY_ID_TYPE));
                    exchange.getIn().setBody(new ErrorInformation((short) 3204, "Party does not exist!"));
                })
                .to("direct:send-parties-callback");

        from("direct:send-parties-callback")
                .id("send-parties-callback")
                .process(exchange ->{
                    Map<String, Object> headers = new HashMap<>();
                    headers.put("Content-Type", PARTIES_CONTENT_TYPE.headerValue());
                    headers.put(FSPIOP_SOURCE.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
                    headers.put(FSPIOP_DESTINATION.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
                    headers.put("Host", accountLookupService);
                    headers.put("Date", exchange.getIn().getHeader("Date"));
                    headers.put("traceparent", exchange.getIn().getHeader("traceparent"));

                    Object tracestate = exchange.getIn().getHeader("tracestate");
                    if (tracestate != null) {
                        headers.put("tracestate", tracestate);
                    }

                    exchange.getIn().removeHeaders("*");
                    exchange.getIn().setHeaders(headers);
                })
                .choice()
                    .when(e -> e.getIn().getBody() instanceof ErrorInformation)
                        .process(pojoToString)
                        .toD("rest:PUT:/parties/${exchangeProperty."+PARTY_ID_TYPE+"}/${exchangeProperty."+PARTY_ID+"}/error?host={{switch.host}}")
                    .otherwise()
                        .process(pojoToString)
                        .toD("rest:PUT:/parties/${exchangeProperty."+PARTY_ID_TYPE+"}/${exchangeProperty."+PARTY_ID+"}?host={{switch.host}}")
                .end();

    }

}
