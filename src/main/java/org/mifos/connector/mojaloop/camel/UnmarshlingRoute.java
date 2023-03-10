package org.mifos.connector.mojaloop.camel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.builder.RouteBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CLASS_TYPE;

@Component
public class UnmarshlingRoute extends RouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void configure() {

        from("direct:body-unmarshling")
                .id("body-unmarshling")
                .process(exchange -> {
                    try {
                        Class type = (Class) exchange.getProperty(CLASS_TYPE);
                        String bdy = exchange.getIn().getBody(String.class);
                        exchange.getIn().setBody(objectMapper.readValue(bdy, type));
                        logger.debug("Converted dto: {}", exchange.getIn().getBody());
                    } catch (Exception e) {
                        logger.error("Error while unmarshling body: {}", e.getMessage());
                    }
                });

    }
}
