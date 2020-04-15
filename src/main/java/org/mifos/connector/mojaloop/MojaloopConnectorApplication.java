package org.mifos.connector.mojaloop;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.Map;
import java.util.stream.Collectors;

@SpringBootApplication
@EnableConfigurationProperties(PartyProperties.class)
public class MojaloopConnectorApplication {

    private Logger headerLogger = LoggerFactory.getLogger("headerLogger");

    public static void main(String[] args) {
        SpringApplication.run(MojaloopConnectorApplication.class, args);
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        return objectMapper
                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Bean
    public Processor pojoToString(ObjectMapper objectMapper) {
        return exchange -> exchange.getIn().setBody(objectMapper.writeValueAsString(exchange.getIn().getBody()));
    }

    @Bean
    public Processor headerLogger() {
        return exchange -> {
            Map<String, Object> headers = exchange.getIn().getHeaders();
            String headersAsString = headers.keySet().stream()
                    .map(key -> key + "=" + headers.get(key))
                    .collect(Collectors.joining("\n"));

            headerLogger.debug("body: {}", exchange.getIn().getBody());
            headerLogger.debug("headers: {}", headersAsString);
        };
    }
}
