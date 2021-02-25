package org.mifos.connector.mojaloop.party;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.mojaloop.dto.PartySwitchResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    private ZeebeClient zeebeClient;

    @Override
    public void process(Exchange exchange) {
        Map<String, Object> variables = new HashMap<>();
        Object isPayeePartyLookupFailed = exchange.getProperty(PARTY_LOOKUP_FAILED);
        String error = exchange.getIn().getBody(String.class);
        if (isPayeePartyLookupFailed != null && (boolean) isPayeePartyLookupFailed) {
            variables.put(ERROR_INFORMATION, error);
            variables.put(PARTY_LOOKUP_FAILED, true);
        } else {
            PartySwitchResponseDTO response = exchange.getIn().getBody(PartySwitchResponseDTO.class);
            variables.put(PARTY_LOOKUP_FSP_ID, response.getParty().getPartyIdInfo().getFspId());
            variables.put(PARTY_LOOKUP_FAILED, false);
        }

        if(zeebeClient != null) {
            zeebeClient.newPublishMessageCommand()
                    .messageName(PARTY_LOOKUP)
                    .correlationKey(exchange.getProperty(CACHED_TRANSACTION_ID, String.class))
                    .timeToLive(Duration.ofMillis(30000))
                    .variables(variables)
                    .send();
        } else {
            logger.error("FSP -> Mojaloop party response error: {}", error);
        }
    }
}