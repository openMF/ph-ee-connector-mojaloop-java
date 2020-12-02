package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.FspMoneyData;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.LOCAL_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_SWITCH_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TIMEOUT_QUOTE_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
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

    @Value("${mojaloop.enabled}")
    private boolean isMojaloopEnabled;

    @Autowired
    private ObjectMapper objectMapper;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspId : dfspids) {
            logger.info("## generating " + WORKER_QUOTE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_QUOTE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                        Map<String, Object> existingVariables = job.getVariablesAsMap();
                        existingVariables.put(TIMEOUT_QUOTE_RETRY_COUNT, 1 + (Integer) existingVariables.getOrDefault(TIMEOUT_QUOTE_RETRY_COUNT, -1));
                        Object quoteId = existingVariables.get(QUOTE_ID);
                        String transactionId = (String) existingVariables.get(TRANSACTION_ID);
                        if (quoteId == null) {
                            quoteId = UUID.randomUUID().toString();
                            existingVariables.put(QUOTE_ID, quoteId);
                        }

                        Exchange exchange = new DefaultExchange(camelContext);
                        if (isMojaloopEnabled) {
                            exchange.setProperty(TRANSACTION_ID, transactionId);
                            exchange.setProperty(CHANNEL_REQUEST, existingVariables.get(CHANNEL_REQUEST));
                            exchange.setProperty(ORIGIN_DATE, existingVariables.get(ORIGIN_DATE));
                            exchange.setProperty(PARTY_LOOKUP_FSP_ID, existingVariables.get(PARTY_LOOKUP_FSP_ID));
                            exchange.setProperty(TENANT_ID, existingVariables.get(TENANT_ID));
                            exchange.setProperty(QUOTE_ID, quoteId);
                            producerTemplate.send("direct:send-quote", exchange);
                        } else {
                            TransactionChannelRequestDTO channelRequest = objectMapper.readValue((String) existingVariables.get(CHANNEL_REQUEST), TransactionChannelRequestDTO.class);
                            QuoteSwitchResponseDTO response = new QuoteSwitchResponseDTO();
                            response.setTransferAmount(channelRequest.getAmount());
                            response.setPayeeFspFee(new FspMoneyData(BigDecimal.ZERO, channelRequest.getAmount().getCurrency()).toMoneyData());
                            response.setPayeeFspCommission(new FspMoneyData(BigDecimal.ZERO, channelRequest.getAmount().getCurrency()).toMoneyData());
                            response.setExpiration("never");
                            response.setIlpPacket("ilp");
                            response.setCondition("condition");

                            exchange.getIn().setBody(response);
                            exchange.getIn().setHeader(QUOTE_ID, quoteId);
                            producerTemplate.send("direct:quotes-step4", exchange);
                        }

                        client.newCompleteCommand(job.getKey())
                                .variables(existingVariables)
                                .send()
                                ;
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
                                ;
                    })
                    .name(WORKER_PAYEE_QUOTE_RESPONSE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();
        }
    }
}
