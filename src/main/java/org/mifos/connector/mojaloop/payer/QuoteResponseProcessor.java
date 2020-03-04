package org.mifos.connector.mojaloop.payer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.phee.common.mojaloop.ilp.Ilp;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class QuoteResponseProcessor implements Processor {

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void process(Exchange exchange) throws JsonProcessingException {
        QuoteSwitchResponseDTO response = exchange.getIn().getBody(QuoteSwitchResponseDTO.class);
        if (!ilpBuilder.isValidPacketAgainstCondition(response.getIlpPacket(), response.getCondition())) {
            throw new RuntimeException("Invalid ILP packet!");
        }
        Ilp ilp = ilpBuilder.parse(response.getIlpPacket(), response.getCondition());
        String transactionId = ilp.getTransaction().getTransactionId();

        Map<String, Object> variables = new HashMap<>();
        variables.put("quoteResponse", objectMapper.writeValueAsString(response));

        zeebeClient.newPublishMessageCommand()
                .messageName("quote")
                .correlationKey(transactionId)
                .timeToLive(Duration.ofMillis(30000))
                .variables(variables)
                .send();
    }
}
