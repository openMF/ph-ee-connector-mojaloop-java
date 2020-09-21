package org.mifos.connector.mojaloop.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PartyIdInfo;
import org.mifos.connector.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.connector.mojaloop.properties.PartyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.common.mojaloop.type.IdentifierType.MSISDN;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.zeebeVariablesToCamelHeaders;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ACCOUNT_CURRENCY;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.INITIATOR_FSP_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.IS_RTP_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_ID_TYPE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_RETRY_COUNT;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYEE_PARTY_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TENANT_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PARTY_LOOKUP_LOCAL_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PARTY_LOOKUP_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeeWorkers.WORKER_PARTY_REGISTRATION_ORACLE;

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

    @Value("${mojaloop.enabled}")
    private boolean isMojaloopEnabled;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspId : dfspids) {
            logger.info("## generating " + WORKER_PARTY_LOOKUP_REQUEST + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_PARTY_LOOKUP_REQUEST + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();
                        existingVariables.put(PARTY_LOOKUP_RETRY_COUNT, 1 + (Integer) existingVariables.getOrDefault(PARTY_LOOKUP_RETRY_COUNT, -1));

                        boolean isTransactionRequest = (boolean) existingVariables.get(IS_RTP_REQUEST);
                        String tenantId = (String) existingVariables.get(TENANT_ID);
                        Object channelRequest = existingVariables.get(CHANNEL_REQUEST);
                        // only saved for operations to identify workflow
                        if (existingVariables.get(INITIATOR_FSP_ID) == null) {
                            TransactionChannelRequestDTO channelRequestObject = objectMapper.readValue((String) channelRequest, TransactionChannelRequestDTO.class);
                            PartyIdInfo initiatorParty = isTransactionRequest ? channelRequestObject.getPayee().getPartyIdInfo() : channelRequestObject.getPayer().getPartyIdInfo();
                            String initiatorFspId = partyProperties.getPartyByTenant(tenantId).getFspId();
                            existingVariables.put(INITIATOR_FSP_ID, initiatorFspId);
                        }

                        Exchange exchange = new DefaultExchange(camelContext);
                        if(isMojaloopEnabled) {
                            exchange.setProperty(TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
                            exchange.setProperty(CHANNEL_REQUEST, channelRequest);
                            exchange.setProperty(ORIGIN_DATE, existingVariables.get(ORIGIN_DATE));
                            exchange.setProperty(IS_RTP_REQUEST, isTransactionRequest);
                            exchange.setProperty(TENANT_ID, tenantId);
                            producerTemplate.send("direct:send-party-lookup", exchange);
                        } else {
                            PartyIdInfo partyIdInfo = new PartyIdInfo(MSISDN, "27710305999", null, "in03tn05");
                            Party party = new Party(partyIdInfo, null, null, null);
                            PartySwitchResponseDTO response = new PartySwitchResponseDTO(party);
                            exchange.getIn().setBody(response);
                            exchange.setProperty(CACHED_TRANSACTION_ID, existingVariables.get(TRANSACTION_ID));
                            producerTemplate.send("direct:parties-step4", exchange);
                        }

                        client.newCompleteCommand(job.getKey())
                                .variables(existingVariables)
                                .send()
                                ;
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
                        Object errorInformation = existingVariables.get(ERROR_INFORMATION);
                        if (errorInformation != null) {
                            zeebeVariablesToCamelHeaders(existingVariables, exchange,
                                    FSPIOP_SOURCE.headerName(),
                                    "traceparent",
                                    "Date"
                            );

                            exchange.setProperty(ERROR_INFORMATION, errorInformation);
                            exchange.setProperty(PARTY_ID_TYPE, existingVariables.get(PARTY_ID_TYPE));
                            exchange.setProperty(PARTY_ID, existingVariables.get(PARTY_ID));

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
                                ;
                    })
                    .name(WORKER_PARTY_LOOKUP_LOCAL_RESPONSE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_PARTY_REGISTRATION_ORACLE + "{} zeebe worker", dfspId);
            zeebeClient.newWorker()
                    .jobType(WORKER_PARTY_REGISTRATION_ORACLE + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> existingVariables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        exchange.setProperty(PARTY_ID_TYPE, existingVariables.get(PARTY_ID_TYPE));
                        exchange.setProperty(PARTY_ID, existingVariables.get(PARTY_ID));
                        exchange.setProperty(TENANT_ID, existingVariables.get(TENANT_ID));
                        exchange.setProperty(ACCOUNT_CURRENCY, existingVariables.get(ACCOUNT_CURRENCY));
                        producerTemplate.send("direct:register-party-identifier-in-oracle", exchange);

                        client.newCompleteCommand(job.getKey())
                                .send()
                                ;
                    })
                    .name(WORKER_PARTY_REGISTRATION_ORACLE + dfspId)
                    .maxJobsActive(workerMaxJobs)
                    .open();
        }
    }
}
