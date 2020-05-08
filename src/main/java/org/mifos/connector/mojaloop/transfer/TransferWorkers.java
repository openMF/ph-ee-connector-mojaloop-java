package org.mifos.connector.mojaloop.transfer;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.SWITCH_TRANSFER_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TIMEOUT_TRANSFER_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.zeebeVariablesToCamelHeaders;

@Component
public class TransferWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Value("#{'${dfspids}'.split(',')}")
    private List<String> dfspids;

    @PostConstruct
    public void setupWorkers() {
        zeebeClient.newWorker()
                .jobType("send-transfer-request")
                .handler((client, job) -> {
                    logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                    Map<String, Object> variables = job.getVariablesAsMap();
                    variables.put(TIMEOUT_TRANSFER_RETRY_COUNT, 1 + (Integer) variables.getOrDefault(TIMEOUT_TRANSFER_RETRY_COUNT, -1));

                    Exchange exchange = new DefaultExchange(camelContext);
                    exchange.setProperty(TRANSACTION_ID, variables.get(TRANSACTION_ID));
                    exchange.setProperty(ORIGIN_DATE, variables.get(ORIGIN_DATE));
                    exchange.getIn().setBody(variables.get(PAYEE_QUOTE_RESPONSE));

                    producerTemplate.send("direct:send-transfer", exchange);
                    client.newCompleteCommand(job.getKey())
                            .variables(variables)
                            .send()
                            .join();
                })
                .name("send-transfer-request")
                .maxJobsActive(10)
                .open();

        for(String dfspid : dfspids) {
            logger.info("## generating payee-transfer-response-{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType("payee-transfer-response-" + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.getIn().setBody(existingVariables.get(SWITCH_TRANSFER_REQUEST));
                        Object errorInformation = existingVariables.get(ERROR_INFORMATION);
                        if(errorInformation != null) {
                            zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    "Date",
                                    "traceparent"
                            );

                            exchange.setProperty(ERROR_INFORMATION, errorInformation);
                            exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
                            producerTemplate.send("direct:send-transfer-error-to-switch", exchange);
                        } else {
                            zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    "Date",
                                    "traceparent"
                            );

                            exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
                            producerTemplate.send("direct:send-transfer-to-switch", exchange);
                        }
                        client.newCompleteCommand(job.getKey())
                                .send()
                                .join();
                    })
                    .name("payee-transfer-response-" + dfspid)
                    .maxJobsActive(10)
                    .open();
        }
    }
}
