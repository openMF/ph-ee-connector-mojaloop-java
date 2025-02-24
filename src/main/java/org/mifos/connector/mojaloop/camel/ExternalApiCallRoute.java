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
                .to("log:myLogger?level=INFO&showAll=true&multiline=true")
                .process(exchange -> {
                    // remove the trailing "/" from endpoint
                    String endpoint = exchange.getProperty(ENDPOINT, String.class);
                    if (endpoint.startsWith("/")) { exchange.setProperty(ENDPOINT, endpoint.substring(1)); 
                        log.info("TDDEBUG EXTERNAL ROUTE Endpoint: {}", exchange.getProperty(ENDPOINT, String.class));
                        log.info("TDDEBUG EXTERNAL ROUTE Host: {}", exchange.getProperty(HOST, String.class));
                        log.info("TDDEBUG EXTERNAL ROUTE Headers: {}", exchange.getIn().getHeaders());  
                        log.info("TDDEBUG exchange HTTP method: {}", exchange.getProperty("CamelHttpMethod", String.class));
                    }
                })
                .log(LoggingLevel.INFO,"Host: ${exchangeProperty." + HOST + "}")
                .log(LoggingLevel.INFO,"Endpoint: ${exchangeProperty." + ENDPOINT + "}")
                .log(LoggingLevel.INFO,"Headers: ${headers}")
                .log(LoggingLevel.INFO,"Request Body: ${body}")
                .setHeader("Date", simple("${date:now:yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}"))
                .toD("${exchangeProperty." + HOST + "}/${exchangeProperty." + ENDPOINT + "}" +
                        "?bridgeEndpoint=true" + "&throwExceptionOnFailure=false" +
                        "&headerFilterStrategy=#" + CUSTOM_HEADER_FILTER_STRATEGY)
                .log(LoggingLevel.INFO,"Response body: ${body}").to("log:org.apache.camel?level=INFO&showAll=true&multiline=true");
    }
}