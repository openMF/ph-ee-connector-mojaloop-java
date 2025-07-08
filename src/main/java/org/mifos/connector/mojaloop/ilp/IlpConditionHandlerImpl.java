/*
 * This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at
 *
 *  https://mozilla.org/MPL/2.0/.
 */
package org.mifos.connector.mojaloop.ilp;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilp.conditions.models.pdp.Party;
import com.ilp.conditions.models.pdp.PartyIdInfo;
import com.ilp.conditions.models.pdp.Transaction;
import com.ilp.conditions.models.pdp.TransactionType;
import org.interledger.Condition;
import org.interledger.Fulfillment;
import org.interledger.InterledgerAddress;
import org.interledger.ilp.InterledgerPayment;
import org.interledger.codecs.CodecContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.Arrays;
import java.util.Base64;
import static java.util.Base64.getUrlDecoder;

@Component
public class IlpConditionHandlerImpl {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private ObjectMapper mapper;

    // @Value("${ilp.secret}")
    // private String conectorIlpSecret;

        public String getILPPacket(String ilpAddress, String amount, Transaction transaction) throws IOException {
        InterledgerAddress address = InterledgerAddress.builder().value(ilpAddress).build();
        InterledgerPayment.Builder paymentBuilder = InterledgerPayment.builder();
        paymentBuilder.destinationAccount(address);
        paymentBuilder.destinationAmount(Long.valueOf(amount));
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson = mapper.writeValueAsString(transaction);
        logger.info("Notification JSON: {}", notificationJson);
        byte[] serializedTransaction = Base64.getUrlEncoder().encode(notificationJson.getBytes());
        paymentBuilder.data(serializedTransaction);
        CodecContext context = CodecContextFactory.interledger();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        context.write(InterledgerPayment.class, paymentBuilder.build(), outputStream);
        return Base64.getUrlEncoder().encodeToString(outputStream.toByteArray());
    }

    public String grok_getILPPacketExactV2(String ilpAddress, String amount, Transaction transaction) throws IOException {
        logger.info("TOMD-ILP-v6 grok_getILPPacketExactV2: ilpAddress: {}, amount: {}, transaction: {}", ilpAddress, amount, transaction);

        // Validate ILP address format
        if (ilpAddress == null || ilpAddress.trim().isEmpty() || !ilpAddress.matches("g\\.[a-zA-Z0-9.-]+\\.[a-zA-Z0-9.-]+\\.[a-zA-Z0-9.-]+")) {
            logger.error("Invalid ILP address: {}", ilpAddress);
            throw new IllegalArgumentException("Invalid ILP address format: " + ilpAddress);
        }

        // Log transaction details for debugging
        logTransactionDetails(transaction);

        // Build packet content
        ByteArrayOutputStream contentStream = new ByteArrayOutputStream();

        // Destination address (with length prefix)
        byte[] addressBytes = ilpAddress.getBytes(StandardCharsets.UTF_8);
        logger.info("Address: {}, bytes: {}, length: {}", ilpAddress, toHexString(addressBytes), addressBytes.length);
        writeVarOctetString(contentStream, addressBytes);
        logger.info("Content stream after address: {}", toHexString(contentStream.toByteArray()));

        // Amount as 8 bytes
        long amountValue;
        try {
            amountValue = Long.parseLong(amount);
            logger.info("Parsed amount: {}", amountValue);
        } catch (NumberFormatException e) {
            logger.error("Invalid amount format: {}", amount, e);
            throw new IOException("Invalid amount format: " + amount, e);
        }
        byte[] amountBytes = longToBytes(amountValue);
        contentStream.write(amountBytes);
        logger.info("Content stream after amount: {}", toHexString(contentStream.toByteArray()));

        // Expiry (empty)
        writeVarOctetString(contentStream, new byte[0]);
        logger.info("Content stream after expiry: {}", toHexString(contentStream.toByteArray()));

        // Data section
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson;
        try {
            notificationJson = mapper.writeValueAsString(transaction);
            mapper.readTree(notificationJson); // Validate JSON
            logger.info("Notification JSON: {}", notificationJson);
            // Check for non-printable characters
            String invalidChars = notificationJson.replaceAll("[\\x20-\\x7E\\n\\r\\t]", "");
            if (!invalidChars.isEmpty()) {
                logger.warn("Invalid characters in JSON: {}, hex: {}", invalidChars, toHexString(invalidChars.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            logger.error("Invalid JSON for transaction: {}", transaction, e);
            throw new IOException("Failed to serialize transaction to valid JSON", e);
        }
        byte[] jsonBytes = notificationJson.getBytes(StandardCharsets.UTF_8);
        logger.info("JSON bytes: {}, length: {}", toHexString(jsonBytes), jsonBytes.length);
        byte[] serializedTransaction = Base64.getUrlEncoder().withoutPadding().encode(jsonBytes);
        logger.info("Serialized transaction: {}, length: {}", toHexString(serializedTransaction), serializedTransaction.length);
        writeVarOctetString(contentStream, serializedTransaction);
        logger.info("Content stream after data: {}", toHexString(contentStream.toByteArray()));

        // Get packet content
        byte[] packetContent = contentStream.toByteArray();
        int contentLength = packetContent.length;
        logger.info("Packet content length: {}", contentLength);

        // Build complete packet with dynamic length
        ByteArrayOutputStream exactStream = new ByteArrayOutputStream();

        // Fixed ILP header (Interledger Protocol v2)
        byte[] header = new byte[] { 0x01, (byte) 0x82, 0x02, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C };
        exactStream.write(header);
        logger.info("Header bytes: {}", toHexString(header));

        // Write dynamic length prefix (OER-style)
        writeVarUInt(exactStream, contentLength);
        logger.info("Stream after length prefix: {}", toHexString(exactStream.toByteArray()));

        // Append packet content
        exactStream.write(packetContent);

        // Encode to Base64
        byte[] rawBytes = exactStream.toByteArray();
        logger.info("Final packet bytes (length: {}): {}", rawBytes.length, toHexString(rawBytes));
        String encodedPacket = Base64.getUrlEncoder().withoutPadding().encodeToString(rawBytes);
        logger.info("Base64 packet: {}", encodedPacket);
        return encodedPacket;
    }

    public Transaction getTransactionFromIlpPacket(String ilpPacket) {
        try {
            byte[] packetBytes = getUrlDecoder().decode(ilpPacket);
            logger.info("Decoded packet bytes: {}, length: {}", toHexString(packetBytes), packetBytes.length);

            // Verify header
            if (packetBytes.length < 11 || packetBytes[0] != 0x01 || packetBytes[1] != (byte) 0x82 || packetBytes[2] != 0x02 || packetBytes[3] != 0x6C || packetBytes[10] != 0x0C) {
                logger.error("Invalid ILP packet header: {}", toHexString(Arrays.copyOf(packetBytes, Math.min(11, packetBytes.length))));
                throw new IOException("Invalid ILP packet header");
            }

            ByteArrayInputStream inputStream = new ByteArrayInputStream(packetBytes);
            inputStream.skip(11); // Skip header
            long contentLength = readVarUInt(inputStream);
            logger.info("Content length: {}", contentLength);

            // Read address
            long addressLength = readVarUInt(inputStream);
            byte[] addressBytes = new byte[(int) addressLength];
            inputStream.read(addressBytes);
            String address = new String(addressBytes, StandardCharsets.UTF_8);
            logger.info("Decoded address: {}", address);

            // Skip amount (8 bytes)
            inputStream.skip(8);

            // Skip expiry
            long expiryLength = readVarUInt(inputStream);
            inputStream.skip(expiryLength);

            // Read data
            long dataLength = readVarUInt(inputStream);
            byte[] dataBytes = new byte[(int) dataLength];
            inputStream.read(dataBytes);
            logger.info("Data bytes: {}, length: {}", toHexString(dataBytes), dataBytes.length);
            byte[] jsonBytes;
            try {
                jsonBytes = getUrlDecoder().decode(dataBytes);
                logger.info("Decoded JSON: {}", new String(jsonBytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                logger.error("Failed to decode Base64 data: {}", toHexString(dataBytes), e);
                throw new IOException("Invalid Base64 data in ILP packet", e);
            }
            Transaction transaction = mapper.readValue(jsonBytes, Transaction.class);
            logger.info("Decoded transaction: {}", transaction);
            return transaction;
        } catch (Exception ex) {
            logger.error("Error decoding ILP packet: {}", ilpPacket, ex);
            return null;
        }
    }

    public String generateFulfillment(String ilpPacket, byte[] secret) {
        byte[] bFulfillment = this.getFulfillmentBytes(ilpPacket, secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bFulfillment);
    }

    public String generateCondition(String ilpPacket, byte[] secret) {
        byte[] bFulfillment = this.getFulfillmentBytes(ilpPacket, secret);
        Fulfillment fulfillment = Fulfillment.builder().preimage(bFulfillment).build();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(fulfillment.getCondition().getHash());
    }

    public boolean validateFulfillmentAgainstCondition(String strFulfillment, String strCondition) {
        byte[] bFulfillment = getUrlDecoder().decode(strFulfillment);
        Fulfillment fulfillment = Fulfillment.of(bFulfillment);
        byte[] bCondition = getUrlDecoder().decode(strCondition);
        Condition condition = Condition.of(bCondition);
        return fulfillment.validate(condition);
    }

    private byte[] getFulfillmentBytes(String ilpPacket, byte[] secret) {
        try {
            String HMAC_ALGORITHM = "HmacSHA256";
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(ilpPacket.getBytes());
        } catch (NoSuchAlgorithmException | IllegalStateException | InvalidKeyException e) {
            throw new RuntimeException("Error getting HMAC", e);
        }
    }

    private void logTransactionDetails(Transaction transaction) {
        logger.info("Transaction details: {}", transaction);
        if (transaction.getTransactionId() != null) logger.info("TransactionId: {}", transaction.getTransactionId());
        if (transaction.getQuoteId() != null) logger.info("QuoteId: {}", transaction.getQuoteId());
        if (transaction.getAmount() != null) {
            logger.info("Amount: {}, Currency: {}", transaction.getAmount().getAmount(), transaction.getAmount().getCurrency());
        }
        if (transaction.getPayer() != null && transaction.getPayer().getPartyIdInfo() != null) {
            PartyIdInfo payer = transaction.getPayer().getPartyIdInfo();
            logger.info("Payer: FspId={}, Type={}, Identifier={}", payer.getFspId(), payer.getPartyIdType(), payer.getPartyIdentifier());
        }
        if (transaction.getPayee() != null && transaction.getPayee().getPartyIdInfo() != null) {
            PartyIdInfo payee = transaction.getPayee().getPartyIdInfo();
            logger.info("Payee: FspId={}, Type={}, Identifier={}", payee.getFspId(), payee.getPartyIdType(), payee.getPartyIdentifier());
        }
        if (transaction.getTransactionType() != null) {
            TransactionType type = transaction.getTransactionType();
            logger.info("TransactionType: Scenario={}, Initiator={}, InitiatorType={}", 
                type.getScenario(), type.getInitiator(), type.getInitiatorType());
        }
    }

    private void writeVarOctetString(ByteArrayOutputStream stream, byte[] data) throws IOException {
        writeVarUInt(stream, data.length);
        stream.write(data);
    }

    private void writeVarUInt(ByteArrayOutputStream stream, long value) throws IOException {
        if (value < 0) throw new IOException("Negative length not allowed: " + value);
        if (value <= 0x7F) {
            stream.write((byte) value);
        } else if (value <= 0x3FFF) {
            stream.write((byte) ((value >> 7) | 0x80));
            stream.write((byte) (value & 0x7F));
        } else if (value <= 0x1FFFFF) {
            stream.write((byte) ((value >> 14) | 0x80));
            stream.write((byte) ((value >> 7) | 0x80));
            stream.write((byte) (value & 0x7F));
        } else {
            throw new IOException("Length too large: " + value);
        }
    }

    private long readVarUInt(ByteArrayInputStream stream) throws IOException {
        int firstByte = stream.read();
        if (firstByte < 0) throw new IOException("Unexpected end of stream");
        if ((firstByte & 0x80) == 0) {
            return firstByte;
        }
        long value = firstByte & 0x7F;
        int shift = 7;
        for (int i = 0; i < 2; i++) {
            int nextByte = stream.read();
            if (nextByte < 0) throw new IOException("Unexpected end of stream");
            value |= (long) (nextByte & 0x7F) << shift;
            if ((nextByte & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("Invalid variable-length integer");
    }

    private String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private byte[] longToBytes(long value) {
        return new byte[] {
            (byte) (value >> 56), (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
            (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value
        };
    }
}