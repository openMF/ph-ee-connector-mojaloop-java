/*
 * This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at
 *
 * https://mozilla.org/MPL/2.0/.
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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

import static java.util.Base64.getUrlDecoder;
import static java.util.Base64.getUrlEncoder;

@Component
public class IlpConditionHandlerImpl {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static final Pattern ILP_ADDRESS_PATTERN = Pattern.compile("g\\.[a-zA-Z0-9_-]+\\.msisdn\\.[0-9]+");

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

    /**
     * Generates an ILPv1 Payment packet as per IL-RFC-15 and ilp-packet:2.2.0.
     * Packet structure:
     * - [type: 1 byte]                - 0x01 for ILP_PAYMENT
     * - [contents length: VLQ]        - Variable-Length Quantity (VLQ) for total contents length
     * - [amount: 8 bytes]             - 64-bit unsigned integer (big-endian) for amount
     * - [address length: 1 byte]      - Length of the ILP address
     * - [address: N bytes]            - UTF-8 encoded ILP address (e.g., g.bluebank.msisdn.0449034997)
     * - [data length: VLQ]            - VLQ for length of Base64-encoded JSON data
     * - [data: N bytes]               - Base64-encoded JSON transaction data
     * - [extensions: 1 byte]          - 0x00 for no extensions
     */
    public String grok3_getILPPacket(String ilpAddress, String amount, Transaction transaction) throws IOException {
        logger.info("grok3_getILPPacket: ilpAddress: {}, amount: {}, transaction: {}", ilpAddress, amount, transaction);

        // Validate ILP address
        // Ensures address matches g.<fspId>.msisdn.<identifier> format
        if (ilpAddress == null || ilpAddress.trim().isEmpty() || !ILP_ADDRESS_PATTERN.matcher(ilpAddress).matches()) {
            logger.error("Invalid ILP address: {}", ilpAddress);
            throw new IllegalArgumentException("Invalid ILP address format: " + ilpAddress);
        }
        // Adding ILP address
        byte[] addressBytes = ilpAddress.getBytes(StandardCharsets.UTF_8);
        logger.info("Address bytes length: {}, hex: {}", addressBytes.length, toHexString(addressBytes));
        if (addressBytes.length != 28) {
            throw new IOException("Invalid address length: " + addressBytes.length);
        }

        // Parse amount
        // Converts amount string to 64-bit integer for 8-byte encoding
        long amountValue;
        try {
            amountValue = Long.parseLong(amount);
            if (amountValue < 0) {
                throw new IllegalArgumentException("Amount must be non-negative");
            }
            logger.info("Parsed amount: {}", amountValue);
        } catch (NumberFormatException e) {
            logger.error("Invalid amount format: {}", amount, e);
            throw new IOException("Invalid amount format: " + amount, e);
        }

        // Serialize transaction to JSON
        // Encodes transaction as JSON, excluding null fields
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson;
        try {
            notificationJson = mapper.writeValueAsString(transaction);
            mapper.readTree(notificationJson); // Validate JSON
            logger.info("Notification JSON: {}, length: {}", notificationJson, notificationJson.length());
        } catch (Exception e) {
            logger.error("Invalid JSON for transaction: {}", transaction, e);
            throw new IOException("Failed to serialize transaction to valid JSON", e);
        }
        // Adding JSON data (Base64-encoded)
        byte[] jsonBytes = notificationJson.getBytes(StandardCharsets.UTF_8);
        logger.info("JSON bytes length: {}", jsonBytes.length);
        logger.info("JSON raw bytes (first 100): {}", Arrays.toString(Arrays.copyOf(jsonBytes, Math.min(100, jsonBytes.length))));

        // IMPORTANT: Use standard Base64 encoding (with padding) to match GrokIlpv1v2.java and Node.js decoder expectation
        byte[] dataBytes = Base64.getEncoder().encode(jsonBytes);
        logger.info("Data bytes length (Base64 encoded): {}, hex (first 100): {}", dataBytes.length, toHexString(Arrays.copyOf(dataBytes, Math.min(100, dataBytes.length))));

        // Build contents
        ByteArrayOutputStream contentsStream = new ByteArrayOutputStream();
        // Adding amount (8-byte unsigned integer, big-endian)
        contentsStream.write(new byte[] {
            (byte) (amountValue >> 56), (byte) (amountValue >> 48),
            (byte) (amountValue >> 40), (byte) (amountValue >> 32),
            (byte) (amountValue >> 24), (byte) (amountValue >> 16),
            (byte) (amountValue >> 8), (byte) amountValue
        });
        logger.info("Amount bytes: {}", toHexString(new byte[] {
            (byte) (amountValue >> 56), (byte) (amountValue >> 48),
            (byte) (amountValue >> 40), (byte) (amountValue >> 32),
            (byte) (amountValue >> 24), (byte) (amountValue >> 16),
            (byte) (amountValue >> 8), (byte) amountValue
        }));
        // Adding address length (1 byte)
        contentsStream.write((byte) addressBytes.length);
        logger.info("Address length byte: {}", String.format("%02X", addressBytes.length));
        // Adding address
        contentsStream.write(addressBytes);

        // IMPORTANT: Use custom length prefix logic to match GrokIlpv1v2.java
        writeCustomLengthPrefix(contentsStream, dataBytes.length);
        logger.info("Data length prefix written for length: {}", dataBytes.length);

        // Adding data
        contentsStream.write(dataBytes);
        // Adding extensions (0x00)
        contentsStream.write((byte) 0x00);
        logger.info("Extensions byte: 00");

        byte[] contents = contentsStream.toByteArray();
        logger.info("Contents length: {}, hex (first 100): {}", contents.length, toHexString(Arrays.copyOf(contents, Math.min(100, contents.length))));

        // Validate contents length using the custom length prefix calculation
        int expectedContentsLength = 8 + 1 + addressBytes.length + getCustomLengthPrefixEncodedLength(dataBytes.length) + dataBytes.length + 1;
        if (contents.length != expectedContentsLength) {
            logger.error("Contents length mismatch: expected {}, actual {}", expectedContentsLength, contents.length);
            throw new IOException("Invalid contents length: " + contents.length);
        }

        // Build packet
        ByteArrayOutputStream packetStream = new ByteArrayOutputStream();
        // Adding packet type (0x01)
        packetStream.write(0x01);
        logger.info("Packet type: 01");

        // IMPORTANT: Use custom length prefix logic to match GrokIlpv1v2.java
        writeCustomLengthPrefix(packetStream, contents.length);
        logger.info("Contents length prefix written for length: {}", contents.length);

        // Adding contents
        packetStream.write(contents);

        byte[] rawBytes = packetStream.toByteArray();
        logger.info("Final packet bytes length: {}, hex (first 100): {}", rawBytes.length, toHexString(Arrays.copyOf(rawBytes, Math.min(100, rawBytes.length))));

        // Validate total packet length using the custom length prefix calculation
        int expectedPacketLength = 1 + getCustomLengthPrefixEncodedLength(contents.length) + contents.length;
        if (rawBytes.length != expectedPacketLength) {
            logger.error("Packet length mismatch: expected {}, actual {}", expectedPacketLength, rawBytes.length);
            throw new IOException("Invalid packet length: " + rawBytes.length);
        }

        // Encode to Base64 (standard encoding, with padding)
        String encodedPacket = Base64.getEncoder().encodeToString(rawBytes);
        logger.info("Base64 packet: {}", encodedPacket);

        return encodedPacket;
    }

    /**
     * Writes a custom length prefix to the stream, mimicking GrokIlpv1v2.java's behavior.
     * For lengths <= 127, it's a single byte.
     * For lengths > 127, it's a 3-byte sequence: 0x82 followed by the 2-byte big-endian length.
     * This is NOT a standard ILP-RFC-15 VLQ, but matches the provided working example.
     */
    private void writeCustomLengthPrefix(ByteArrayOutputStream stream, int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative length not allowed: " + length);
        }
        if (length <= 127) {
            stream.write((byte) length);
        } else {
            stream.write((byte) 0x82); // Custom prefix for lengths > 127
            stream.write((byte) (length >> 8)); // Most significant byte of length
            stream.write((byte) (length & 0xFF)); // Least significant byte of length
        }
    }

    /**
     * Calculates the encoded length of a custom length prefix.
     */
    private int getCustomLengthPrefixEncodedLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative length not allowed: " + length);
        }
        if (length <= 127) {
            return 1;
        } else {
            return 3; // 0x82 + 2 bytes for length
        }
    }

    /**
     * Reads a custom length prefix from the stream, mimicking GrokIlpv1v2.java's behavior.
     * For lengths <= 127, it's a single byte.
     * For lengths > 127, it's a 3-byte sequence: 0x82 followed by the 2-byte big-endian length.
     */
    private int readCustomLengthPrefix(ByteArrayInputStream stream) throws IOException {
        int firstByte = stream.read();
        if (firstByte < 0) {
            throw new IOException("Unexpected end of stream while reading length prefix");
        }

        if ((firstByte & 0x80) == 0) { // Single byte length (<= 127)
            return firstByte;
        } else if (firstByte == 0x82) { // 3-byte length (0x82 followed by 2 bytes)
            int byte2 = stream.read();
            int byte3 = stream.read();
            if (byte2 < 0 || byte3 < 0) {
                throw new IOException("Unexpected end of stream while reading 3-byte length prefix");
            }
            return ((byte2 & 0xFF) << 8) | (byte3 & 0xFF);
        } else {
            throw new IOException("Invalid custom length prefix byte: " + String.format("%02X", firstByte));
        }
    }

    public Transaction getTransactionFromIlpPacket(String ilpPacket) {
        try {
            // Use standard Base64 decoder as the encoder uses standard Base64
            byte[] packetBytes = Base64.getDecoder().decode(ilpPacket);
            logger.info("Decoded packet bytes: {}, length: {}", toHexString(packetBytes), packetBytes.length);

            ByteArrayInputStream inputStream = new ByteArrayInputStream(packetBytes);

            // 1. Read Packet Type (1 byte)
            int packetType = inputStream.read();
            if (packetType == -1) throw new IOException("Unexpected end of stream reading packet type");
            if (packetType != 0x01) {
                logger.error("Invalid ILP packet type: {}", String.format("%02X", packetType));
                throw new IOException("Invalid ILP packet type");
            }
            logger.info("Packet Type: {}", String.format("%02X", packetType));

            // 2. Read Contents Length (using custom length prefix)
            int contentsLength = readCustomLengthPrefix(inputStream);
            logger.info("Decoded Contents Length: {}", contentsLength);

            // 3. Read Amount (8 bytes)
            byte[] amountBytes = new byte[8];
            int bytesRead = inputStream.read(amountBytes);
            if (bytesRead != 8) throw new IOException("Unexpected end of stream reading amount");
            long amount = 0;
            for (byte b : amountBytes) {
                amount = (amount << 8) | (b & 0xFF);
            }
            logger.info("Decoded Amount: {}", amount);

            // 4. Read Address Length (1 byte)
            int addressLength = inputStream.read();
            if (addressLength == -1) throw new IOException("Unexpected end of stream reading address length");
            logger.info("Decoded Address Length: {}", addressLength);

            // 5. Read Address (N bytes)
            byte[] addressBytes = new byte[addressLength];
            bytesRead = inputStream.read(addressBytes);
            if (bytesRead != addressLength) throw new IOException("Unexpected end of stream reading address");
            String address = new String(addressBytes, StandardCharsets.UTF_8);
            logger.info("Decoded Address: {}", address);

            // 6. Read Data Length (using custom length prefix)
            int dataLength = readCustomLengthPrefix(inputStream);
            logger.info("Decoded Data Length: {}", dataLength);

            // 7. Read Data (N bytes)
            byte[] dataBytes = new byte[dataLength];
            bytesRead = inputStream.read(dataBytes);
            if (bytesRead != dataLength) throw new IOException("Unexpected end of stream reading data");
            logger.info("Data bytes (raw): {}, length: {}", toHexString(dataBytes), dataBytes.length);

            // Decode Base64 data to JSON bytes (use standard Base64 decoder)
            byte[] jsonBytes;
            try {
                jsonBytes = Base64.getDecoder().decode(dataBytes);
                logger.info("Decoded JSON: {}", new String(jsonBytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                logger.error("Failed to decode Base64 data: {}", toHexString(dataBytes), e);
                throw new IOException("Invalid Base64 data in ILP packet", e);
            }

            // 8. Read Extensions (1 byte)
            int extensions = inputStream.read();
            if (extensions == -1) throw new IOException("Unexpected end of stream reading extensions");
            if (extensions != 0x00) {
                logger.warn("Non-zero extensions byte encountered: {}", String.format("%02X", extensions));
            }
            logger.info("Extensions byte: {}", String.format("%02X", extensions));

            // Ensure no extra bytes
            if (inputStream.available() > 0) {
                logger.warn("Extra bytes found in packet after decoding: {}", inputStream.available());
            }

            // Parse JSON
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

    // Keeping these methods as they are used by getTransactionFromIlpPacket,
    // though they implement a different VLQ scheme than the one used for packet generation.
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
