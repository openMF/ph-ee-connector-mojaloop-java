package org.mifos.connector.mojaloop.payer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSFER_STATE;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;


@Component
public class TransferResponseProcessor implements Processor {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) {
        logger.info("######## SWITCH -> {} - response for transfer request - STEP 3", exchange.getIn().getHeader(FSPIOP_DESTINATION.headerName()));
        TransferSwitchResponseDTO response = exchange.getIn().getBody(TransferSwitchResponseDTO.class);

        Map<String, Object> variables = new HashMap<>();
        variables.put(TRANSFER_STATE, response.getTransferState().name());

        zeebeClient.newPublishMessageCommand()
                .messageName("transfer-response")
                .correlationKey(exchange.getProperty(CACHED_TRANSACTION_ID, String.class))
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send();
    }
}
