package org.mifos.connector.mojaloop.party;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TENANT_ID;

@Component
public class OracleRoutes extends ErrorHandlerRouteBuilder {

    private static final String ORIGINAL_HEADERS_PROPERTY = "originalHeaders";

    @Autowired
    private PartyProperties partyProperties;

    public OracleRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("direct:get-dfsp-from-oracle")
                .id("get-dfsp-from-oracle")
                .process(e -> {
                    e.setProperty(ORIGINAL_HEADERS_PROPERTY, e.getIn().getHeaders());
                    e.getIn().getHeaders().remove("CamelHttpPath");
                    e.getIn().getHeaders().remove("CamelHttpUri");
                })
                .toD("rest:GET:/oracle/participants/${header." + PARTY_ID_TYPE + "}/${header." + PARTY_ID + "}?host={{switch.oracle-host}}")
                .process(e -> {
                    JSONObject oracleResponse = new JSONObject(e.getIn().getBody(String.class));
                    JSONArray partyList = oracleResponse.getJSONArray("partyList");
                    if (partyList.length() != 1) {
                        throw new RuntimeException("Can not identify dfsp from oracle with type: " + e.getIn().getHeader(PARTY_ID_TYPE, String.class) + " and value: "
                                + e.getIn().getHeader(PARTY_ID, String.class) + ", response contains " + partyList.length() + " elements!");
                    }
                    String tenantId = partyProperties.getPartyByDfsp(partyList.getJSONObject(0).getString("fspId")).getTenantId();
                    e.setProperty(TENANT_ID, tenantId);
                });

        from("direct:add-party-to-dfsp-in-oracle")
                .id("get-dfsp-from-oracle")
                .process(e -> {
                    e.setProperty(ORIGINAL_HEADERS_PROPERTY, e.getIn().getHeaders());
                    e.getIn().getHeaders().remove("CamelHttpPath");
                    e.getIn().getHeaders().remove("CamelHttpUri");
                    JSONObject request = new JSONObject();
                })
                .toD("rest:GET:/oracle/participants/${header." + PARTY_ID_TYPE + "}/${header." + PARTY_ID + "}?host={{switch.oracle-host}}")
                .process(e -> {
                    JSONObject oracleResponse = new JSONObject(e.getIn().getBody(String.class));
                    JSONArray partyList = oracleResponse.getJSONArray("partyList");
                    if (partyList.length() != 1) {
                        throw new RuntimeException("Can not identify dfsp from oracle with type: " + e.getIn().getHeader(PARTY_ID_TYPE, String.class) + " and value: "
                                + e.getIn().getHeader(PARTY_ID, String.class) + ", response contains " + partyList.length() + " elements!");
                    }
                    String tenantId = partyProperties.getPartyByDfsp(partyList.getJSONObject(0).getString("fspId")).getTenantId();
                    e.setProperty(TENANT_ID, tenantId);
                });
    }
}
