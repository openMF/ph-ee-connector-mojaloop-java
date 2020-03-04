package org.mifos.connector.mojaloop.payer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;


@Component
public class TransferResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) {
        TransferSwitchResponseDTO response = exchange.getIn().getBody(TransferSwitchResponseDTO.class);

        String cachedTransactionId = exchange.getProperty(CamelProperties.CACHED_TRANSACTION_ID, String.class);

        Map<String, Object> variables = new HashMap<>();
        variables.put("transactionStatus", "200");

        zeebeClient.newPublishMessageCommand()
                .messageName("transfer-prepare")
                .correlationKey(cachedTransactionId)
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send();
    }
}
