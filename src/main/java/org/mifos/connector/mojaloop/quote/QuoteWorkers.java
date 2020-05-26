package org.mifos.connector.mojaloop.quote;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.LOCAL_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_SWITCH_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TIMEOUT_QUOTE_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_QUOTE;

@Component
public class QuoteWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Value("#{'${dfspids}'.split(',')}")
    private List<String> dfspids;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspId : dfspids) {
            logger.info("## generating " + WORKER_QUOTE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_QUOTE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Exchange exchange = new DefaultExchange(camelContext);
                        Map<String, Object> variables = job.getVariablesAsMap();
                        variables.put(TIMEOUT_QUOTE_RETRY_COUNT, 1 + (Integer) variables.getOrDefault(TIMEOUT_QUOTE_RETRY_COUNT, -1));

                        exchange.setProperty(TRANSACTION_ID, variables.get(TRANSACTION_ID));
                        exchange.setProperty(CHANNEL_REQUEST, variables.get(CHANNEL_REQUEST));
                        exchange.setProperty(ORIGIN_DATE, variables.get(ORIGIN_DATE));
                        exchange.setProperty(PARTY_LOOKUP_FSP_ID, variables.get(PARTY_LOOKUP_FSP_ID));
                        Object quoteId = variables.get(QUOTE_ID);
                        if (quoteId == null) {
                            quoteId = UUID.randomUUID().toString();
                            variables.put(QUOTE_ID, quoteId);
                        }
                        exchange.setProperty(QUOTE_ID, quoteId);

                        producerTemplate.send("direct:send-quote", exchange);

                        client.newCompleteCommand(job.getKey())
                                .variables(variables)
                                .send()
                                .join();
                    })
                    .name(WORKER_QUOTE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_PAYEE_QUOTE_RESPONSE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_PAYEE_QUOTE_RESPONSE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.getIn().setBody(existingVariables.get(QUOTE_SWITCH_REQUEST));
                        Object errorInformation = existingVariables.get(ERROR_INFORMATION);
                        if (errorInformation != null) {
                            ZeebeProcessStarter.zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    FSPIOP_SOURCE.headerName(),
                                    FSPIOP_DESTINATION.headerName(),
                                    "Date",
                                    "traceparent"
                            );

                            exchange.setProperty(ERROR_INFORMATION, errorInformation);
                            producerTemplate.send("direct:send-quote-error-to-switch", exchange);
                        } else {
                            ZeebeProcessStarter.zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    FSPIOP_SOURCE.headerName(),
                                    FSPIOP_DESTINATION.headerName(),
                                    "Date",
                                    "traceparent",
                                    LOCAL_QUOTE_RESPONSE
                            );

                            producerTemplate.send("direct:send-quote-to-switch", exchange);
                        }
                        client.newCompleteCommand(job.getKey())
                                .send()
                                .join();
                    })
                    .name(WORKER_PAYEE_QUOTE_RESPONSE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();
        }
    }
}
