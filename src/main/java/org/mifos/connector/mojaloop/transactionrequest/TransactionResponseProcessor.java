package org.mifos.connector.mojaloop.transactionrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.mojaloop.dto.TransactionRequestSwitchResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_REQUEST_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_REQUEST_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_STATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.TRANSACTION_REQUEST;

@Component
public class TransactionResponseProcessor implements Processor {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    private ZeebeClient zeebeClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        Object isTransactionRequestFailed = exchange.getProperty(TRANSACTION_REQUEST_FAILED);
        String error = exchange.getIn().getBody(String.class);

        if (isTransactionRequestFailed != null && (boolean)isTransactionRequestFailed) {
            variables.put(ERROR_INFORMATION, error);
            variables.put(TRANSACTION_REQUEST_FAILED, true);
        } else {
            TransactionRequestSwitchResponseDTO response = exchange.getIn().getBody(TransactionRequestSwitchResponseDTO.class);
            variables.put(TRANSACTION_REQUEST_RESPONSE, objectMapper.writeValueAsString(response));
            variables.put(TRANSACTION_REQUEST_FAILED, false);
            variables.put(TRANSACTION_STATE, response.getTransactionRequestState().name()); // TODO prepare for pending state?
        }

        if(zeebeClient != null) {
            zeebeClient.newPublishMessageCommand()
                    .messageName(TRANSACTION_REQUEST)
                    .correlationKey(exchange.getIn().getHeader(TRANSACTION_ID, String.class))
                    .timeToLive(Duration.ofMillis(30000))
                    .variables(variables)
                    .send();
        } else {
            logger.error("Mojaloop transactionRequest request failed: {}", error);
        }
    }
}
