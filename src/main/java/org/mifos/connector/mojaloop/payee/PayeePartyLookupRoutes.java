package org.mifos.connector.mojaloop.payee;


import org.mifos.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.common.mojaloop.dto.Party;
import org.mifos.common.mojaloop.dto.PartyIdInfo;
import org.mifos.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.common.mojaloop.type.IdentifierType;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.interop.SwitchOutRouteBuilder.PARTIES_CONTENT_TYPE_HEADER;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.camelHeadersToZeebeVariables;

@Component
public class PayeePartyLookupRoutes extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${switch.account-lookup-service}")
    private String accountLookupService;

    @Value("${bpmn.flows.party-lookup}")
    private String partyLookupFlow;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    /* TODO remove mock lookup response from this map */
    private Map<String, String> msisdnFspIdMap = new HashMap<>();

    public PayeePartyLookupRoutes() {
        super.configure();
    }

    @PostConstruct
    public void setup() {
        msisdnFspIdMap.put("27710501999", "localdev01");
        msisdnFspIdMap.put("27710502999", "localdev02");
    }

    @Override
    public void configure() {
        // inbound Party Lookup from SWITCH
        from("rest:GET:/switch/parties/{partyIdType}/{partyId}")
                .log(LoggingLevel.WARN, "## SWITCH -> HUB inbound GET parties - STEP 2")
                .process(exchange ->
                        zeebeProcessStarter.startZeebeWorkflow(partyLookupFlow, exchange.getIn().getBody(String.class), variables ->
                                camelHeadersToZeebeVariables(exchange, variables,
                                        "partyIdType",
                                        "partyId",
                                        "fspiop-source",
                                        "traceparent",
                                        "Date")
                        )
                );

        from("direct:send-parties-callback")
                .process(exchange -> {
                    String partyId = exchange.getIn().getHeader("partyId", String.class);
                    String partyIdType = exchange.getIn().getHeader("partyIdType", String.class);
                    String targetFspId = msisdnFspIdMap.get(partyId);

                    Party party = new Party(
                            new PartyIdInfo(IdentifierType.valueOf(partyIdType),
                                    partyId, null, targetFspId), null, null, null
                    );
                    PartySwitchResponseDTO response = new PartySwitchResponseDTO(party);
                    exchange.getIn().setBody(response);
                    exchange.setProperty("partyId", partyId);
                    exchange.setProperty("partyIdType", partyIdType);

                    Map<String, Object> headers = new HashMap<>();
                    headers.put("Content-Type", PARTIES_CONTENT_TYPE_HEADER);
                    headers.put("fspiop-source", exchange.getIn().getHeader("fspiop-source"));
                    headers.put("fspiop-destination", exchange.getIn().getHeader("fspiop-source"));
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
                .process(pojoToString)
                .toD("rest:PUT:/parties/${exchangeProperty.partyIdType}/${exchangeProperty.partyId}?host={{switch.host}}");
    }

}
