package org.mifos.connector.mojaloop.payer;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.mifos.phee.common.mojaloop.dto.PartySwitchResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.PAYEE_PARTY_LOOKUP_FAILED;

@Component
public class PartiesResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> variables = new HashMap<>();
        if(exchange.getProperty(PAYEE_PARTY_LOOKUP_FAILED, Boolean.class)) {
            variables.put(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
            variables.put(PAYEE_PARTY_LOOKUP_FAILED, true);
        } else {
            PartySwitchResponseDTO response = exchange.getIn().getBody(PartySwitchResponseDTO.class);
            variables.put(CamelProperties.PAYEE_FSP_ID, response.getParty().getPartyIdInfo().getFspId());
        }

        zeebeClient.newPublishMessageCommand()
                .messageName("payee-user-lookup")
                .correlationKey(exchange.getProperty(CACHED_TRANSACTION_ID, String.class))
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send();
    }
}
