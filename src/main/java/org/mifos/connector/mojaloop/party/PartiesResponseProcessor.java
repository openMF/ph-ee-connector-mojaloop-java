package org.mifos.connector.mojaloop.party;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.mojaloop.dto.PartySwitchResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.CACHED_TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PARTY_LOOKUP_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.PARTY_LOOKUP;

@Component
public class PartiesResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> variables = new HashMap<>();
        Object isPayeePartyLookupFailed = exchange.getProperty(PARTY_LOOKUP_FAILED);
        if (isPayeePartyLookupFailed != null && (boolean) isPayeePartyLookupFailed) {
            variables.put(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
            variables.put(PARTY_LOOKUP_FAILED, true);
        } else {
            PartySwitchResponseDTO response = exchange.getIn().getBody(PartySwitchResponseDTO.class);
            variables.put(PARTY_LOOKUP_FSP_ID, response.getParty().getPartyIdInfo().getFspId());
            variables.put(PARTY_LOOKUP_FAILED, false);
        }

        zeebeClient.newPublishMessageCommand()
                .messageName(PARTY_LOOKUP)
                .correlationKey(exchange.getProperty(CACHED_TRANSACTION_ID, String.class))
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send()
                ;
    }
}
