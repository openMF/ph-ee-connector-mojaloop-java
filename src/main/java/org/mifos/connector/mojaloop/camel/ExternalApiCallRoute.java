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
                    log.info("TDDEBUG EXTERNAL ROUTE Endpoint: {}", endpoint);
                    if (endpoint.startsWith("/")) { 
                        exchange.setProperty(ENDPOINT, endpoint.substring(1)); 
                    }

                    log.info("TDDEBUG-MODIFIED EXTERNAL ROUTE Endpoint: {}", exchange.getProperty(ENDPOINT, String.class));
                    log.info("TDDEBUG EXTERNAL ROUTE Host: {}", exchange.getProperty(HOST, String.class));
                    
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
                    
                    log.info("TDDEBUG EXTERNAL ROUTE Response Body: {}", responseBodyStr);
                    log.info("TDDEBUG EXTERNAL ROUTE Response Headers: {}", exchange.getIn().getHeaders());
                });
    }


//*************** use this code below after debugging ************/
// @Component
// public class ExternalApiCallRoute extends RouteBuilder {
    // @Override
    // public void configure() {
    //     from("direct:external-api-call")
    //             .id("external-api-call")
    //             .log(LoggingLevel.DEBUG,"######## API CALL -> Calling an external api")
    //             .process(exchange -> {
    //                 // remove the trailing "/" from endpoint
    //                 String endpoint = exchange.getProperty(ENDPOINT, String.class);
    //                 log.info("TDDEBUG EXTERNAL ROUTE Endpoint: {}", endpoint);
    //                 if (endpoint.startsWith("/")) { exchange.setProperty(ENDPOINT, endpoint.substring(1)); }

    //                 // TOMD 
    //                 // Set the headers here for testing but fix connector-common to fix properly 
    //                 // vNext uses later versions for accept header and content type
    //                 // ALSO: NOTE this might fail for participant calls or quotes and transfers 
    //                 // exchange.getIn().setHeader("Accept", "application/vnd.interoperability.parties+json;version=1.1");
    //                 // exchange.getIn().setHeader("Content-Type", "application/vnd.interoperability.parties+json;version=1.1");
    //                 // exchange.getIn().setHeader("Date", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    //                 // exchange.getIn().setHeader("fspiop-date", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
 
    //                 // TOMD Debugging 

    //                 log.info("TDDEBUG-MODIFIED EXTERNAL ROUTE Endpoint: {}", exchange.getProperty(ENDPOINT, String.class));
    //                 log.info("TDDEBUG EXTERNAL ROUTE Host: {}", exchange.getProperty(HOST, String.class));
    //                 log.info("TDDEBUG EXTERNAL ROUTE Headers: \n{}\n{}", 
    //                     exchange.getIn().getHeaders().entrySet().stream()
    //                             .map(entry -> entry.getKey() + ": " + entry.getValue())
    //                             .collect(Collectors.joining("\n")),
    //                     "------------------------".repeat(5));
    //                 // ObjectMapper mapper = new ObjectMapper();
    //                 // try {
    //                 //     String bodyString = (String) exchange.getIn().getBody();
    //                 //     JsonNode jsonNode = mapper.readTree(bodyString);
    //                 //     String prettyBody = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);
    //                 //     log.info("TDDEBUG exchange Body: \n{}", prettyBody);
    //                 // } catch (IOException e) {
    //                 //     log.info("TDDEBUG not pretty printed but here is the exchange Body: \n{}", exchange.getIn().getBody());
    //                 // }
                
    //                 // log.info("TDDEBUG exchange Body: \n{}", exchange.getIn().getBody());

    //             })
    //             .log(LoggingLevel.INFO,"Host: ${exchangeProperty." + HOST + "}")
    //             .log(LoggingLevel.INFO,"Endpoint: ${exchangeProperty." + ENDPOINT + "}")
    //             .log(LoggingLevel.INFO,"Headers: ${headers}")
    //             .log(LoggingLevel.INFO,"Request Body: ${body}")
    //             .toD("${exchangeProperty." + HOST + "}/${exchangeProperty." + ENDPOINT + "}" +
    //                     "?bridgeEndpoint=true" + "&throwExceptionOnFailure=true" +
    //                     "&headerFilterStrategy=#" + CUSTOM_HEADER_FILTER_STRATEGY)
    //             .log(LoggingLevel.INFO,"######## API CALL -> Received Response") // Added a log to mark the response
    //             .log(LoggingLevel.INFO,"Response Headers: ${headers}") // Log response headers
    //             .log(LoggingLevel.INFO,"Response Body: ${body}")    // Log response body
    //             .process(exchange -> {
    //                 // Process the response here if needed
    //                 // For example, you can set the response body to a property
    //                 String responseBody = exchange.getIn().getBody(String.class);
    //                 exchange.setProperty("responseBody", responseBody);
    //                 log.info("TDDEBUG EXTERNAL ROUTE Response Body: {}", responseBody);
    //                 log.info("TDDEBUG EXTERNAL ROUTE Response Headers: {}", exchange.getIn().getHeaders());
    //             });
    // }

    // @Override
    // public void configure() {
    //     from("direct:external-api-call")
    //             .id("external-api-call")
    //             .log(LoggingLevel.INFO,"######## API CALL -> Calling an external api")
    //             .to("log:myLogger?level=INFO&showAll=true&multiline=true")
    //             .process(exchange -> {
    //                 // remove the trailing "/" from endpoint
    //                 String endpoint = exchange.getProperty(ENDPOINT, String.class);
    //                 String host = exchange.getProperty(HOST, String.class);
    //                 while (host.endsWith("//")) {
    //                     host = host.substring(0, host.length() - 1);
    //                 }
                    
                    // // Add proper HTTP protocol
                    // if (!host.startsWith("http://") && !host.startsWith("https://")) {
                    //     host = "http://" + host;
                    // }
                    
                    // // Append port if not already included
                    // if (!host.contains(":53013")) {
                    //     host = host + ":53013";
                    // }
                    
                    // exchange.setProperty(HOST, host);
                    
                    // if (endpoint.startsWith("/")) {
                    //     exchange.setProperty(ENDPOINT, endpoint.substring(1));
                    // }
                    // // Set the HTTP method if it's not already set
                    // if (exchange.getProperty("CamelHttpMethod", String.class) == null) {
                    //     // Default to GET if no method is specified
                    //     exchange.getIn().setHeader("CamelHttpMethod", "PUT");
                    // }
                //     log.info("TDDEBUG EXTERNAL ROUTE Endpoint: {}", exchange.getProperty(ENDPOINT, String.class));
                //     log.info("TDDEBUG EXTERNAL ROUTE Host: {}", exchange.getProperty(HOST, String.class));
                //     log.info("TDDEBUG EXTERNAL ROUTE Headers: {}", exchange.getIn().getHeaders());
                //     log.info("TDDEBUG exchange HTTP method: {}", exchange.getProperty("CamelHttpMethod", String.class));
                //     log.info("TDDEBUG exchange Body: {}", exchange.getIn().getBody() ) ;

                // })
                // .setHeader("Date", simple("${date:now:yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}"))
                // .toD("${exchangeProperty." + HOST + "}/${exchangeProperty." + ENDPOINT + "}" +
                //         "?bridgeEndpoint=true" + "&throwExceptionOnFailure=false" +
                //         "&headerFilterStrategy=#" + CUSTOM_HEADER_FILTER_STRATEGY)
                // .log(LoggingLevel.INFO,"TOMD API CALL -> Response from external api")
                // .log(LoggingLevel.INFO,"TOMD-RESP Response body: ${body}").to("log:org.apache.camel?level=INFO&showAll=true&multiline=true");
    // }
}