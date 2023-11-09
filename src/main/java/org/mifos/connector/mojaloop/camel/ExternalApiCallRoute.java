package org.mifos.connector.mojaloop.camel;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.*;

@Component
public class ExternalApiCallRoute extends RouteBuilder {

    @Override
    public void configure() {
        from("direct:external-api-call")
                .id("external-api-call")
                .log(LoggingLevel.INFO,"######## API CALL -> Calling an external api")
                .process(exchange -> {
                    // remove the trailing "/" from endpoint
                    String endpoint = exchange.getProperty(ENDPOINT, String.class);
                    if (endpoint.startsWith("/")) { exchange.setProperty(ENDPOINT, endpoint.substring(1)); }
                })
                .log(LoggingLevel.INFO,"Host: ${exchangeProperty." + HOST + "}")
                .log(LoggingLevel.INFO,"Endpoint: ${exchangeProperty." + ENDPOINT + "}")
                .log(LoggingLevel.INFO,"Headers: ${headers}")
                .log(LoggingLevel.INFO,"Request Body: ${body}")
                .toD("${exchangeProperty." + HOST + "}/${exchangeProperty." + ENDPOINT + "}" +
                        "?bridgeEndpoint=true" + "&throwExceptionOnFailure=false" +
                        "&headerFilterStrategy=#" + CUSTOM_HEADER_FILTER_STRATEGY)
                .log(LoggingLevel.INFO,"Response body: ${body}");
    }
}
