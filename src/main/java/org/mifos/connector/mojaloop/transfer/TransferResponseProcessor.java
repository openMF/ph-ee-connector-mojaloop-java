package org.mifos.connector.mojaloop.transfer;

import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSFER_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSFER_STATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.TRANSFER_RESPONSE;


@Component
public class TransferResponseProcessor implements Processor {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> variables = new HashMap<>();
        Object isPayeeTransferFailed = exchange.getProperty(TRANSFER_FAILED);
        String error = exchange.getIn().getBody(String.class);
        if (isPayeeTransferFailed != null && (boolean)isPayeeTransferFailed) {
            variables.put(ERROR_INFORMATION, error);
            variables.put(TRANSFER_FAILED, true);
        } else {
            variables.put(TRANSFER_STATE, exchange.getIn().getBody(TransferSwitchResponseDTO.class).getTransferState().name());
            variables.put(TRANSFER_FAILED, false);
        }

        if(zeebeClient != null) {
            zeebeClient.newPublishMessageCommand()
                    .messageName(TRANSFER_RESPONSE)
                    .correlationKey(exchange.getProperty(CACHED_TRANSACTION_ID, String.class))
                    .timeToLive(Duration.ofMillis(30000))
                    .variables(variables)
                    .send();
        } else {
            logger.error("Mojaloop transfer request failed: {}", error);
        }
    }
}
