package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.PAYEE_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.QUOTE_FAILED;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE;

@Component
public class QuoteResponseProcessor implements Processor {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
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
        Object isPayeeQuoteFailed = exchange.getProperty(QUOTE_FAILED);

        if (isPayeeQuoteFailed != null && (boolean)isPayeeQuoteFailed) {
            variables.put(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
            variables.put(QUOTE_FAILED, true);
        } else {
            QuoteSwitchResponseDTO response = exchange.getIn().getBody(QuoteSwitchResponseDTO.class);
            if (isMojaloopEnabled && !ilpBuilder.isValidPacketAgainstCondition(response.getIlpPacket(), response.getCondition())) {
                logger.error("Invalid ILP packet for quote: {}", exchange.getIn().getHeader(QUOTE_ID));
                variables.put(QUOTE_FAILED, true);
            }
            variables.put(PAYEE_QUOTE_RESPONSE, objectMapper.writeValueAsString(response));
            variables.put(QUOTE_FAILED, false);
        }

        zeebeClient.newPublishMessageCommand()
                .messageName(QUOTE)
                .correlationKey(exchange.getIn().getHeader(QUOTE_ID, String.class))
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send()
                ;
    }
}
