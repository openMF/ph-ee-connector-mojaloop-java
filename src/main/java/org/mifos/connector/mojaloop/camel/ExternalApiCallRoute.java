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
                .log(LoggingLevel.DEBUG,"######## API CALL -> Calling an external api")
                .process(exchange -> {
                    // remove the trailing "/" from endpoint
                    String endpoint = exchange.getProperty(ENDPOINT, String.class);
                    if (endpoint.startsWith("/")) { exchange.setProperty(ENDPOINT, endpoint.substring(1)); }
                })
                .log(LoggingLevel.DEBUG,"Host: ${exchangeProperty." + HOST + "}")
                .log(LoggingLevel.DEBUG,"Endpoint: ${exchangeProperty." + ENDPOINT + "}")
                .log(LoggingLevel.DEBUG,"Headers: ${headers}")
                .log(LoggingLevel.DEBUG,"Request Body: ${body}")
                .toD("${exchangeProperty." + HOST + "}/${exchangeProperty." + ENDPOINT + "}" +
                        "?bridgeEndpoint=true" + "&throwExceptionOnFailure=false" +
                        "&headerFilterStrategy=#" + CUSTOM_HEADER_FILTER_STRATEGY)
                .log(LoggingLevel.DEBUG,"Response body: ${body}");
    }
}
