package org.mifos.connector.mojaloop.zeebe;

import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnExpression("!${mojaloop.perf-mode:false}")
public class ZeebeClientConfiguration {

    @Value("${zeebe.broker.contactpoint}")
    private String zeebeBrokerContactpoint;

    @Value("${zeebe.client.max-execution-threads}")
    private int zeebeClientMaxThreads;

    @Bean
    public ZeebeClient setup() {
        return ZeebeClient.newClientBuilder()
                .gatewayAddress(zeebeBrokerContactpoint)
                .usePlaintext()
                .defaultJobPollInterval(Duration.ofMillis(1))
                .defaultJobWorkerMaxJobsActive(2000)
                .numJobWorkerExecutionThreads(zeebeClientMaxThreads)
                .build();
    }
}
