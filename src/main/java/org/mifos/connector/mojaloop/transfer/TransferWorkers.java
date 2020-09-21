package org.mifos.connector.mojaloop.transfer;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.common.mojaloop.type.TransferState.COMMITTED;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.zeebeVariablesToCamelHeaders;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.SWITCH_TRANSFER_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TIMEOUT_TRANSFER_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PAYEE_TRANSFER_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_SEND_TRANSFER_REQUEST;

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

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @Value("${mojaloop.enabled}")
    private boolean isMojaloopEnabled;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspid : dfspids) {
            logger.info("## generating " + WORKER_PAYEE_TRANSFER_RESPONSE + "{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType(WORKER_PAYEE_TRANSFER_RESPONSE + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.getIn().setBody(existingVariables.get(SWITCH_TRANSFER_REQUEST));
                        Object errorInformation = existingVariables.get(ERROR_INFORMATION);
                        if (errorInformation != null) {
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
                                ;
                    })
                    .name(WORKER_PAYEE_TRANSFER_RESPONSE + dfspid)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_SEND_TRANSFER_REQUEST + "{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType(WORKER_SEND_TRANSFER_REQUEST + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> variables = job.getVariablesAsMap();
                        variables.put(TIMEOUT_TRANSFER_RETRY_COUNT, 1 + (Integer) variables.getOrDefault(TIMEOUT_TRANSFER_RETRY_COUNT, -1));

                        Exchange exchange = new DefaultExchange(camelContext);
                        Object transactionId = variables.get(TRANSACTION_ID);
                        if(isMojaloopEnabled) {
                            exchange.setProperty(TRANSACTION_ID, transactionId);
                            exchange.setProperty(ORIGIN_DATE, variables.get(ORIGIN_DATE));
                            exchange.setProperty(CHANNEL_REQUEST, variables.get(CHANNEL_REQUEST));
                            exchange.getIn().setBody(variables.get(PAYEE_QUOTE_RESPONSE));
                            producerTemplate.send("direct:send-transfer", exchange);
                        } else {
                            TransferSwitchResponseDTO response = new TransferSwitchResponseDTO();
                            response.setTransferState(COMMITTED);
                            exchange.getIn().setBody(response);
                            exchange.getIn().setHeader(TRANSACTION_ID, transactionId);
                            exchange.setProperty(CACHED_TRANSACTION_ID, transactionId);
                            producerTemplate.send("direct:transfers-step4", exchange);
                        }

                        client.newCompleteCommand(job.getKey())
                                .variables(variables)
                                .send()
                                ;
                    })
                    .name(WORKER_SEND_TRANSFER_REQUEST + dfspid)
                    .maxJobsActive(workerMaxJobs)
                    .open();
        }
    }
}
