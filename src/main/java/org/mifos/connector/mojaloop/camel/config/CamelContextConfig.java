package org.mifos.connector.mojaloop.camel.config;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class CamelContextConfig {

    @Value("${camel.server-port}")
    private int serverPort;

    @Bean
    CamelContextConfiguration contextConfiguration() {
        return new CamelContextConfiguration() {
            @Override
            public void beforeApplicationStart(CamelContext camelContext) {
                camelContext.disableJMX();

                RestConfiguration rest = new RestConfiguration();
                rest.setComponent("undertow");
                rest.setProducerComponent("undertow");
                rest.setPort(serverPort);
                rest.setBindingMode(RestConfiguration.RestBindingMode.json);
                rest.setScheme("http");
                camelContext.setRestConfiguration(rest);
            }

            @Override
            public void afterApplicationStart(CamelContext camelContext) {
                // empty
            }
        };
    }
}
