package org.mifos.connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import javax.annotation.PostConstruct;

@SpringBootApplication
@EnableConfigurationProperties(PartyProperties.class)
public class MojaloopConnectorApplication {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${mojaloop.perf-mode}")
    private boolean mojaPerfMode;

    public static void main(String[] args) {
        SpringApplication.run(MojaloopConnectorApplication.class, args);
    }

    @PostConstruct
    public void setup() {
        if(mojaPerfMode) {
            logger.info("----- PERF mode is turned on, no AMS and ZEEBE is running! -----");
        }
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
}
