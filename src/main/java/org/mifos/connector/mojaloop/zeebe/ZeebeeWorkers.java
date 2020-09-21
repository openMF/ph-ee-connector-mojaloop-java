package org.mifos.connector.mojaloop.zeebe;

import io.zeebe.client.ZeebeClient;
import org.mifos.connector.common.mojaloop.type.TransactionRequestState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.AUTH_VALIDATION_SUCCESS;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYER_CONFIRMED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_STATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.ACCEPT_QUOTE;


@Component
public class ZeebeeWorkers {

    public static final String WORKER_GENERATE_OTP = "generate-otp-";
    public static final String WORKER_PARTY_LOOKUP_REQUEST = "party-lookup-request-";
    public static final String WORKER_PARTY_LOOKUP_LOCAL_RESPONSE = "party-lookup-local-response-";
    public static final String WORKER_PAYEE_QUOTE_RESPONSE = "payee-quote-response-";
    public static final String WORKER_PAYER_REQUEST_CONFIRM = "payer-request-confirm-";
    public static final String WORKER_PAYEE_TRANSFER_RESPONSE = "payee-transfer-response-";
    public static final String WORKER_QUOTE = "quote-";
    public static final String WORKER_SEND_AUTH_CONFIRMATION = "send-auth-confirmation-";
    public static final String WORKER_SEND_AUTH_RESPONSE = "send-auth-response-";
    public static final String WORKER_SEND_TRANSACTION_STATE_RESPONSE = "send-transaction-state-response-";
    public static final String WORKER_SEND_TRANSFER_REQUEST = "send-transfer-request-";
    public static final String WORKER_TRANSACTION_REQUEST = "transaction-request-";
    public static final String WORKER_VALIDATE_OTP_AUTH_REPONSE = "validate-otp-auth-reponse-";
    public static final String WORKER_SEND_PAYER_AUTHORISATION = "send-payer-authorisation-";
    public static final String WORKER_PARTY_REGISTRATION_ORACLE = "party-registration-oracle-";

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Value("#{'${dfspids}'.split(',')}")
    private List<String> dfspids;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @PostConstruct
    public void setupWorkers() {
        // TODO move this worker
        for (String dfspid : dfspids) {
            logger.info("## generating " + WORKER_PAYER_REQUEST_CONFIRM + "{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType(WORKER_PAYER_REQUEST_CONFIRM + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> variables = new HashMap<>();
                        variables.put(PAYER_CONFIRMED, true);
                        // TODO these should be somewhere else
                        variables.put(AUTH_VALIDATION_SUCCESS, true);
                        variables.put(TRANSACTION_STATE, TransactionRequestState.ACCEPTED.name());

                        // TODO sum local and payee quote and send to customer

                        zeebeClient.newPublishMessageCommand()
                                .messageName(ACCEPT_QUOTE)
                                .correlationKey((String) job.getVariablesAsMap().get(TRANSACTION_ID))
                                .timeToLive(Duration.ofMillis(30000))
                                .send()
                                ;

                        client.newCompleteCommand(job.getKey())
                                .variables(variables)
                                .send()
                                ;
                    })
                    .name(WORKER_PAYER_REQUEST_CONFIRM + dfspid)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_GENERATE_OTP + "{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType(WORKER_GENERATE_OTP + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                        client.newCompleteCommand(job.getKey())
                                .send()
                                ;
                    })
                    .name(WORKER_GENERATE_OTP + dfspid)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_VALIDATE_OTP_AUTH_REPONSE + "{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType(WORKER_VALIDATE_OTP_AUTH_REPONSE + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                        client.newCompleteCommand(job.getKey())
                                .send()
                                ;
                    })
                    .name(WORKER_VALIDATE_OTP_AUTH_REPONSE + dfspid)
                    .maxJobsActive(workerMaxJobs)
                    .open();

            logger.info("## generating " + WORKER_SEND_PAYER_AUTHORISATION + "{} zeebe worker", dfspid);
            zeebeClient.newWorker()
                    .jobType(WORKER_SEND_PAYER_AUTHORISATION + dfspid)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                        client.newCompleteCommand(job.getKey())
                                .send()
                                ;
                    })
                    .name(WORKER_SEND_PAYER_AUTHORISATION + dfspid)
                    .maxJobsActive(workerMaxJobs)
                    .open();
        }
    }
}