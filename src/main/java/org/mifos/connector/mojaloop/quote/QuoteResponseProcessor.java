package org.mifos.connector.mojaloop.quote;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.mojaloop.ilp.Ilp;
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
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.QUOTE_CALLBACK_ID;

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
        Object isQuoteFailed = exchange.getProperty(QUOTE_FAILED);
        String quoteId = exchange.getIn().getHeader(QUOTE_ID, String.class);

        String quoteCallbackId;
        if (isQuoteFailed != null && (boolean)isQuoteFailed) {
            variables.put(ERROR_INFORMATION, exchange.getIn().getBody(String.class));
            variables.put(QUOTE_FAILED, true);
            quoteCallbackId = quoteId;
        } else {
            QuoteSwitchResponseDTO response = exchange.getIn().getBody(QuoteSwitchResponseDTO.class);
            if (isMojaloopEnabled && !ilpBuilder.isValidPacketAgainstCondition(response.getIlpPacket(), response.getCondition())) {
                logger.error("Invalid ILP packet for quote: {}", exchange.getIn().getHeader(QUOTE_ID));
                variables.put(QUOTE_FAILED, true);
            }
            Ilp ilp = ilpBuilder.parse(response.getIlpPacket(), response.getCondition());
            quoteCallbackId = ilp.getTransaction().getTransactionId() + "-" + quoteId;
            variables.put(PAYEE_QUOTE_RESPONSE, objectMapper.writeValueAsString(response));
            variables.put(QUOTE_FAILED, false);
        }

        zeebeClient.newPublishMessageCommand()
                .messageName(QUOTE_CALLBACK_ID)
                .correlationKey(quoteCallbackId)
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send()
                ;
    }
}
