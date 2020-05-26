package org.mifos.connector.mojaloop.transactionrequest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.dto.TransactionRequestSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.type.TransactionRequestState;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.common.mojaloop.type.TransactionRequestState.ACCEPTED;
import static org.mifos.connector.common.mojaloop.type.TransactionRequestState.PENDING;
import static org.mifos.connector.common.mojaloop.type.TransactionRequestState.RECEIVED;
import static org.mifos.connector.common.mojaloop.type.TransactionRequestState.REJECTED;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_REQUEST_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.PAYER_CONFIRMED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.QUOTE_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TRANSACTION_REQUEST_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeExpressionVariables.TRANSACTION_STATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.TRANSACTION_REQUEST;

@Component
public class TransactionResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> variables = new HashMap<>();
        Object isTransactionRequestFailed = exchange.getProperty(TRANSACTION_REQUEST_FAILED);

        if (isTransactionRequestFailed != null && (boolean)isTransactionRequestFailed) {
            variables.put(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
            variables.put(TRANSACTION_REQUEST_FAILED, true);
        } else {
            TransactionRequestSwitchResponseDTO response = exchange.getIn().getBody(TransactionRequestSwitchResponseDTO.class);
            variables.put(TRANSACTION_REQUEST_RESPONSE, objectMapper.writeValueAsString(response));
            variables.put(TRANSACTION_REQUEST_FAILED, false);
            variables.put(TRANSACTION_STATE, response.getTransactionRequestState().name()); // TODO prepare for pending state?
        }

        zeebeClient.newPublishMessageCommand()
                .messageName(TRANSACTION_REQUEST)
                .correlationKey(exchange.getIn().getHeader(TRANSACTION_ID, String.class))
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send()
                .join();
    }
}
