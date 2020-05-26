package org.mifos.connector.mojaloop.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.IS_RTP_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYER_FSP_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_SWITCH_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.PARTY_LOOKUP_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.zeebeVariablesToCamelHeaders;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PARTY_LOOKUP_LOCAL_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PARTY_LOOKUP_REQUEST;

@Component
public class PartyLookupWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Autowired
    private PartyProperties partyProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("#{'${dfspids}'.split(',')}")
    private List<String> dfspids;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspId : dfspids) {
            logger.info("## generating " + WORKER_PARTY_LOOKUP_REQUEST + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_PARTY_LOOKUP_REQUEST + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> variables = job.getVariablesAsMap();
                        variables.put(PARTY_LOOKUP_RETRY_COUNT, 1 + (Integer) variables.getOrDefault(PARTY_LOOKUP_RETRY_COUNT, -1));

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.setProperty(TRANSACTION_ID, variables.get(TRANSACTION_ID));
                        Object channelRequest = variables.get(CHANNEL_REQUEST);
                        exchange.setProperty(CHANNEL_REQUEST, channelRequest);
                        exchange.setProperty(ORIGIN_DATE, variables.get(ORIGIN_DATE));
                        exchange.setProperty(IS_RTP_REQUEST, variables.get(IS_RTP_REQUEST));
                        producerTemplate.send("direct:send-party-lookup", exchange);

                        // only saved for operations to identify workflow
                        if (variables.get(PAYER_FSP_ID) == null) {
                            TransactionChannelRequestDTO channelRequestObject = objectMapper.readValue((String) channelRequest, TransactionChannelRequestDTO.class);
                            String payerFspId = partyProperties.getParty(channelRequestObject.getPayer().getPartyIdInfo().getPartyIdType().name(),
                                    channelRequestObject.getPayer().getPartyIdInfo().getPartyIdentifier())
                                    .getFspId();
                            variables.put(PAYER_FSP_ID, payerFspId);
                        }

                        client.newCompleteCommand(job.getKey())
                                .variables(variables)
                                .send()
                                .join();
                    })
                    .name(WORKER_PARTY_LOOKUP_REQUEST + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_PARTY_LOOKUP_LOCAL_RESPONSE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_PARTY_LOOKUP_LOCAL_RESPONSE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.getIn().setBody(existingVariables.get(QUOTE_SWITCH_REQUEST));
                        Object errorInformation = existingVariables.get(ERROR_INFORMATION);
                        if (errorInformation != null) {
                            zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    FSPIOP_SOURCE.headerName(),
                                    "traceparent",
                                    "Date"
                            );
                            exchange.setProperty(ERROR_INFORMATION, errorInformation);

                            producerTemplate.send("direct:send-parties-error-response", exchange);
                        } else {
                            zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    FSPIOP_SOURCE.headerName(),
                                    "traceparent",
                                    "Date"
                            );
                            exchange.setProperty(PAYEE_PARTY_RESPONSE, existingVariables.get(PAYEE_PARTY_RESPONSE));

                            producerTemplate.send("direct:send-parties-response", exchange);
                        }

                        client.newCompleteCommand(job.getKey())
                                .send()
                                .join();
                    })
                    .name(WORKER_PARTY_LOOKUP_LOCAL_RESPONSE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();
        }
    }
}
