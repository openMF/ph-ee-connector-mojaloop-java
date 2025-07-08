package org.mifos.connector.mojaloop.ilp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilp.conditions.models.pdp.Money;
import com.ilp.conditions.models.pdp.Party;
import com.ilp.conditions.models.pdp.PartyIdInfo;
import com.ilp.conditions.models.pdp.Transaction;
import com.ilp.conditions.models.pdp.TransactionType;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class IlpConditionHandlerImplTest {

    private static final Logger logger = LoggerFactory.getLogger(IlpConditionHandlerImplTest.class);

    @Autowired
    private IlpConditionHandlerImpl ilpConditionHandler;

    @Test
    public void testGrokGetILPPacketExactV2Transfer() throws IOException {
        // Set up test data
        String ilpAddress = "g.bluebank.msisdn.0449034997";
        String amount = "5000";
        Transaction transaction = new Transaction();
        transaction.setTransactionId("bf0f7d82-1349-4d54-a80b-c5fe25e38e53");
        transaction.setQuoteId("quote456");
        transaction.setPayer(new Party(){{
                setPartyIdInfo(new PartyIdInfo(){{
                        setPartyIdType("MSISDN");
                        setPartyIdentifier("0464189670");
                    }});
                }});
        transaction.setPayee(new Party(){{
                setPartyIdInfo(new PartyIdInfo(){{
                        setPartyIdType("MSISDN");
                        setPartyIdentifier("0449034997");
                    }});
                }});
        Money money = new Money();
        money.setAmount("5000");
        money.setCurrency("USD");
        transaction.setAmount(money);
        TransactionType type = new TransactionType();
        type.setScenario("TRANSFER");
        type.setInitiator("PAYER");
        type.setInitiatorType("CONSUMER");
        transaction.setTransactionType(type);

        // Generate ILP packet
        String packet = ilpConditionHandler.grok_getILPPacketExactV2(ilpAddress, amount, transaction);
        assertNotNull(packet);
        logger.info("Generated transfer packet: {}", packet);

        // Save packet for vnext-decode-v2.cjs
        Files.writeString(Paths.get("transfer_packet.txt"), packet);

        // Decode and verify packet
        Transaction decoded = ilpConditionHandler.getTransactionFromIlpPacket(packet);
        assertNotNull(decoded);
        assertEquals("bf0f7d82-1349-4d54-a80b-c5fe25e38e53", decoded.getTransactionId());
    }
}