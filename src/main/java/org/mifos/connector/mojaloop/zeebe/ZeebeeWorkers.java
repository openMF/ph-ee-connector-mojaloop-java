package org.mifos.connector.mojaloop.zeebe;

import io.zeebe.client.ZeebeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.PAYER_CONFIRMED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.ACCEPT_QUOTE;


@Component
public class ZeebeeWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @PostConstruct
    public void setupWorkers() {
        zeebeClient.newWorker()
                .jobType("payer-request-confirm")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                    client.newCompleteCommand(job.getKey()).send();

                    // TODO sum local and payee quote and send to customer

                    Map<String, Object> variables = new HashMap<>();
                    variables.put(PAYER_CONFIRMED, true);

                    zeebeClient.newPublishMessageCommand()
                            .messageName(ACCEPT_QUOTE)
                            .correlationKey((String) job.getVariablesAsMap().get(TRANSACTION_ID))
                            .timeToLive(Duration.ofMillis(30000))
                            .variables(variables)
                            .send();
                })
                .name("payer-request-confirm")
                .maxJobsActive(10)
                .open();
    }
}