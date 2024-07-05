package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.model.QuoteCallbackDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE_CALLBACK;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE_ERROR;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_ID;

@Component
public class QuoteResponseProcessor implements Processor {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired(required = false)
    private ZeebeClient zeebeClient;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${mojaloop.enabled}")
    private boolean isMojaloopEnabled;

    @Override
    public void process(Exchange exchange) throws JsonProcessingException {
        Map<String, Object> variables = new HashMap<>();

        String messageName;
        String error = exchange.getIn().getBody(String.class);
        if (exchange.getProperty(QUOTE_FAILED, false, Boolean.class)) {
            messageName = QUOTE_ERROR;
            variables.put(ERROR_INFORMATION, error);
            variables.put(QUOTE_FAILED, true);
        } else {
            QuoteCallbackDTO response = exchange.getIn().getBody(QuoteCallbackDTO.class);
            logger.info("ILP PACKET: {}", response.getIlpPacket());
            logger.info("CONDITION: {}", response.getCondition());
            messageName = QUOTE_CALLBACK;
            variables.put(PAYEE_QUOTE_RESPONSE, objectMapper.writeValueAsString(response));
            if (isMojaloopEnabled && !ilpBuilder.isValidPacketAgainstCondition(response.getIlpPacket(), response.getCondition())) {
                logger.error("Invalid ILP packet for quote: {}", exchange.getIn().getHeader(QUOTE_ID));
                variables.put(QUOTE_FAILED, true);
            } else {
                variables.put(QUOTE_FAILED, false);
            }
        }

        if(zeebeClient != null) {
            zeebeClient.newPublishMessageCommand()
                    .messageName(messageName)
                    .correlationKey(exchange.getIn().getHeader(QUOTE_ID, String.class))
                    .timeToLive(Duration.ofMillis(30000))
                    .variables(variables)
                    .send();
        } else {
            logger.error("Mojaloop quote request failed: {}", error);
        }
    }
}
