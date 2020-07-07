package org.mifos.connector.mojaloop.party;

import org.apache.camel.LoggingLevel;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_EXISTS;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ACCOUNT_CURRENCY;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TENANT_ID;

@Component
public class OracleRoutes extends ErrorHandlerRouteBuilder {

    @Autowired
    private PartyProperties partyProperties;

    public OracleRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        // @formatter:off
        from("direct:register-party-identifier-in-oracle")
                .log(LoggingLevel.INFO, "######## registering party identifier ${exchangeProperty." + PARTY_ID + "} with type ${exchangeProperty." + PARTY_ID_TYPE + "} in oracle")
                .id("register-party-identifier-in-oracle")
                .to("direct:get-dfsp-from-oracle")
                .choice()
                    .when(e -> e.getProperty(PARTY_EXISTS, Boolean.class))
                        .to("direct:remove-party-identifier-from-dfsp-in-oracle")
                        .to("direct:add-party-identifier-to-dfsp-in-oracle")
                        .endChoice()
                    .otherwise()
                        .to("direct:add-party-identifier-to-dfsp-in-oracle")
                        .endChoice()
                .end();
        // @formatter:on

        from("direct:get-dfsp-from-oracle")
                .id("get-dfsp-from-oracle")
                .removeHeaders("*")
                .toD("rest:GET:/oracle/participants/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.oracle-host}}")
                .process(e -> {
                    try {
                        e.setProperty(PARTY_EXISTS, !new JSONArray(e.getIn().getBody(String.class)).isEmpty());
                    } catch (JSONException ex) { // non exist and existing response format are different from oracle
                        e.setProperty(PARTY_EXISTS, true);
                    }
                });

        from("direct:add-party-identifier-to-dfsp-in-oracle")
                .id("add-party-identifier-to-dfsp-in-oracle")
                .removeHeaders("*")
                .process(e -> {
                    String fspId = partyProperties.getPartyByTenant(e.getProperty(TENANT_ID, String.class)).getFspId();
                    JSONObject request = new JSONObject();
                    request.put("fspId", fspId);
                    request.put("currency", e.getProperty(ACCOUNT_CURRENCY, String.class));
                    e.getIn().setBody(request.toString());
                    e.getIn().setHeader("Content-Type", "application/json");
                    e.getIn().setHeader("Accept", "application/json");
                })
                .toD("rest:POST:/oracle/participants/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.oracle-host}}");

        from("direct:remove-party-identifier-from-dfsp-in-oracle")
                .id("remove-party-to-dfsp-in-oracle")
                .removeHeaders("*")
                .toD("rest:DELETE:/oracle/participants/${exchangeProperty." + PARTY_ID_TYPE + "}/${exchangeProperty." + PARTY_ID + "}?host={{switch.oracle-host}}");
    }
}
