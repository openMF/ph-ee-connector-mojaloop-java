package org.mifos.connector.mojaloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mifos.connector.common.ams.dto.QuoteFspResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

@SpringBootTest
public class QuoteTest {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    public void testLocalQuoteDeserialization() throws IOException {
        String localQuoteResponseString = "{\"transactionCode\":\"cf1f9731-d248-4cc9-be4c-7937f2ed6c18\",\"quoteCode\":\"4c94ba50-3c6e-46b6-b1ae-1b89baa2b126\",\"state\":\"ACCEPTED\",\"fspFee\":{\"amount\":0,\"currency\":\"USD\"}}";
        QuoteFspResponseDTO localQuoteResponse = objectMapper.readValue(localQuoteResponseString, QuoteFspResponseDTO.class);

        logger.debug("local quote dto: {}", objectMapper.writeValueAsString(localQuoteResponse));
    }

    @Test
    public void testDateDeser() throws IOException {
        String json = "{}";
        QuoteFspResponseDTO testDto = objectMapper.readValue(json, QuoteFspResponseDTO.class);

        logger.debug("Test dto: {}", testDto);
    }
}
