package org.mifos.connector.mojaloop.camel;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.*;

import java.io.IOException;
import java.util.stream.Collectors;


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
                    if (endpoint.startsWith("/")) { 
                        exchange.setProperty(ENDPOINT, endpoint.substring(1)); 
                    }
                    
                    // Pretty print REQUEST HEADERS
                    StringBuilder headerOutput = new StringBuilder();
                    headerOutput.append("\n================== REQUEST HEADERS ==================\n");
                    var headers = exchange.getIn().getHeaders();
                    if (headers.isEmpty()) {
                        headerOutput.append("(No headers)\n");
                    } else {
                        for (var entry : headers.entrySet()) {
                            headerOutput.append(String.format("%-30s: %s\n", entry.getKey(), entry.getValue()));
                        }
                    }
                    headerOutput.append("====================================================");
                    log.info(headerOutput.toString());

                    // Pretty print REQUEST BODY
                    Object body = exchange.getIn().getBody();
                    StringBuilder bodyOutput = new StringBuilder();
                    bodyOutput.append("\n=================== REQUEST BODY ===================\n");
                    
                    if (body == null) {
                        bodyOutput.append("(No body)\n");
                    } else {
                        String bodyString = body.toString();
                        if (bodyString == null || bodyString.trim().isEmpty()) {
                            bodyOutput.append("(Empty body)\n");
                        } else {
                            // Try to format as JSON
                            try {
                                ObjectMapper mapper = new ObjectMapper();
                                JsonNode jsonNode = mapper.readTree(bodyString);
                                String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
                                bodyOutput.append(prettyJson).append("\n");
                            } catch (Exception e) {
                                // Not JSON, just display as-is
                                bodyOutput.append(bodyString).append("\n");
                            }
                        }
                    }
                    bodyOutput.append("====================================================");
                    log.info(bodyOutput.toString());
                })
                .log(LoggingLevel.INFO,"Host: ${exchangeProperty." + HOST + "}")
                .log(LoggingLevel.INFO,"Endpoint: ${exchangeProperty." + ENDPOINT + "}")
                .toD("${exchangeProperty." + HOST + "}/${exchangeProperty." + ENDPOINT + "}" +
                        "?bridgeEndpoint=true" + "&throwExceptionOnFailure=true" +
                        "&headerFilterStrategy=#" + CUSTOM_HEADER_FILTER_STRATEGY)
                .log(LoggingLevel.INFO,"######## API CALL -> Received Response")
                .process(exchange -> {
                    // Pretty print RESPONSE HEADERS
                    StringBuilder responseHeaderOutput = new StringBuilder();
                    responseHeaderOutput.append("\n================== RESPONSE HEADERS =================\n");
                    var responseHeaders = exchange.getIn().getHeaders();
                    if (responseHeaders.isEmpty()) {
                        responseHeaderOutput.append("(No headers)\n");
                    } else {
                        for (var entry : responseHeaders.entrySet()) {
                            responseHeaderOutput.append(String.format("%-30s: %s\n", entry.getKey(), entry.getValue()));
                        }
                    }
                    responseHeaderOutput.append("====================================================");
                    log.info(responseHeaderOutput.toString());

                    // Pretty print RESPONSE BODY
                    Object responseBody = exchange.getIn().getBody();
                    StringBuilder responseBodyOutput = new StringBuilder();
                    responseBodyOutput.append("\n================== RESPONSE BODY ===================\n");
                    
                    if (responseBody == null) {
                        responseBodyOutput.append("(No body)\n");
                    } else {
                        String responseBodyString = responseBody.toString();
                        if (responseBodyString == null || responseBodyString.trim().isEmpty()) {
                            responseBodyOutput.append("(Empty body)\n");
                        } else {
                            // Try to format as JSON
                            try {
                                ObjectMapper mapper = new ObjectMapper();
                                JsonNode jsonNode = mapper.readTree(responseBodyString);
                                String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
                                responseBodyOutput.append(prettyJson).append("\n");
                            } catch (Exception e) {
                                // Not JSON, just display as-is
                                responseBodyOutput.append(responseBodyString).append("\n");
                            }
                        }
                    }
                    responseBodyOutput.append("====================================================");
                    log.info(responseBodyOutput.toString());

                    // Store response body as property if needed
                    String responseBodyStr = exchange.getIn().getBody(String.class);
                    exchange.setProperty("responseBody", responseBodyStr);
                    
                    log.info("GAZELLE-DBG EXTERNAL ROUTE Response Body: {}", responseBodyStr);
                    log.info("GAZELLE-DBG EXTERNAL ROUTE Response Headers: {}", exchange.getIn().getHeaders());
                });
    }

}