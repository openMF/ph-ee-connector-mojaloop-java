package org.mifos.connector.mojaloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilp.conditions.models.pdp.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.ilp.IlpConditionHandlerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

/*
 * ILP_PACKET = random + ilp address + base64(txnPayload)
 * F -> HMCASHA256(ilpPacket, secret)
 * C -> SHA256(F)
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class IlpTestCase {

    private Transaction transaction;

    private String ilpPacket;

    private String condition;

    private String fulfillment;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    public ObjectMapper objectMapper;

    @Autowired
    public IlpBuilder ilpBuilder;

    @Autowired
    public IlpConditionHandlerImpl ilpConditionHandler;

    @Value("${connector.ilp-secret}")
    private String ilpSecret;

    @BeforeAll
    public void init() {
        mockTransaction();
    }

    @BeforeEach
    public void generateIlpPacket() throws IOException {
        String ilpAddress = ilpBuilder.buildIlpAddress(transaction);
        this.ilpPacket = ilpConditionHandler.getILPPacket(ilpAddress, "100",
                transaction);
    }

    public void mockTransaction() {
        transaction = new Transaction(){{
            setAmount(new Money(){{
                setAmount("100");
                setCurrency("USD");
            }});
            setPayer(new Party(){{
                setPartyIdInfo(new PartyIdInfo(){{
                    setPartyIdType("MSISDN");
                    setPartyIdentifier("123123123");
                }});
            }});
            setPayee(new Party(){{
                setPartyIdInfo(new PartyIdInfo(){{
                    setPartyIdType("MSISDN");
                    setPartyIdentifier("321321321");
                }});
            }});
            setTransactionType(new TransactionType(){{
                setScenario("TRANSFER");
                setInitiator("PAYER");
                setInitiatorType("CONSUMER");
            }});
        }};
    }

    // generate ILP
    @Test
    public void checkIfIlpPacketIsNotNull() {
        logger.debug("ILP Packet: {}", this.ilpPacket);
        assert !ilpPacket.isEmpty();
    }

    // check if secret is not null
    @Test
    public void secretNotNull() {
        assert ilpSecret != null;
        logger.debug("Secret: {}", ilpSecret);
    }

    // get condition from ILP packet
    @Test
    public void getConditionFromIlpPacket() throws IOException {
        String condition = ilpConditionHandler.generateCondition(
                this.ilpPacket, ilpSecret.getBytes());
        logger.debug("Condition is {}", condition);
        this.condition = condition;
        assert !condition.isEmpty();
    }

    // get fulfillment from ILP packet
    @Test
    public void getFulfillmentFromIlpPacket() throws IOException {
        String fulfillment = ilpConditionHandler.generateFulfillment(
                this.ilpPacket, ilpSecret.getBytes());
        logger.debug("Fulfillment is {}", condition);
        this.fulfillment = fulfillment;
        assert !this.fulfillment.isEmpty();
    }

    // extract transaction from ilpPacket
    @Test
    public void extractTxnFromIlpPacket() throws IOException {
        logger.debug("Using ilppacket: {}", this.ilpPacket);
        Transaction transaction = ilpConditionHandler.getTransactionFromIlpPacket(this.ilpPacket);
        assert transaction != null;
        logger.debug("Transaction extracted: {}", objectMapper.writeValueAsString(transaction));
    }


}
