package org.mifos.connector.mojaloop.payer;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class PartiesResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        PartySwitchResponseDTO response = exchange.getIn().getBody(PartySwitchResponseDTO.class);
        String cachedTransactionId = exchange.getProperty(CamelProperties.CACHED_TRANSACTION_ID, String.class);

        Map<String, Object> variables = new HashMap<>();
        variables.put("isValidUserLookup", true);
        variables.put(CamelProperties.PAYEE_FSP_ID, response.getParty().getPartyIdInfo().getFspId());

        zeebeClient.newPublishMessageCommand()
                .messageName("payee-user-lookup")
                .correlationKey(cachedTransactionId)
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send();
    }
}
