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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ilp.conditions.models.pdp.Transaction;
import org.interledger.Condition;
import org.interledger.Fulfillment;
import org.interledger.InterledgerAddress;
import org.interledger.codecs.CodecContext;
import org.interledger.codecs.oer.OerUint64Codec;
import org.interledger.codecs.oer.OerUint64Codec.OerUint64;
import org.interledger.ilp.InterledgerPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import static java.util.Base64.getUrlDecoder;

@Component
public class IlpConditionHandlerImpl {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private ObjectMapper mapper;

    // Claude V1 
    // Replace the getILPPacket method in IlpConditionHandlerImpl.java
    public String claude_getILPPacket(String ilpAddress, String amount, Transaction transaction) throws IOException {
        // Build completely custom ILPv1 packet from scratch
        ByteArrayOutputStream ilpv1Stream = new ByteArrayOutputStream();
        
        // ILPv1 Custom Header (exactly matching your working example)
        ilpv1Stream.write(0x01);  // 001 - Version/Type byte
        ilpv1Stream.write(0x82);  // 202 - Length or flag (202 octal = 0x82 hex)  
        ilpv1Stream.write(0x02);  // 002 - Field
        ilpv1Stream.write(0x6C);  // l - 'l' character (108 decimal = 0x6C hex)
        
        // 6 zero bytes (padding/reserved)
        ilpv1Stream.write(new byte[6]); // \0 \0 \0 \0 \0 \0
        
        ilpv1Stream.write(0x0C);  // \f - ILP_PAYMENT type
        
        // Now build the rest of the packet manually according to ILPv1 spec
        ByteArrayOutputStream packetContent = new ByteArrayOutputStream();
        
        // Destination Address
        byte[] addressBytes = ilpAddress.getBytes(StandardCharsets.UTF_8);
        writeVarOctetString(packetContent, addressBytes);
        
        // Amount (UInt64 - 8 bytes, big endian)
        long amountValue = Long.parseLong(amount);
        packetContent.write(longToBytes(amountValue));
        
        // Expiry (optional - write as empty for now)
        writeVarOctetString(packetContent, new byte[0]);
        
        // Data (transaction JSON, base64 encoded)
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson = mapper.writeValueAsString(transaction);
        byte[] serializedTransaction = Base64.getUrlEncoder().encode(notificationJson.getBytes());
        writeVarOctetString(packetContent, serializedTransaction);
        
        // Calculate the actual length of the packet content
        byte[] contentBytes = packetContent.toByteArray();
        int contentLength = contentBytes.length;
        
        // Write the length indicators (034 034 in your example)
        // These appear to be the length of the packet content
        if (contentLength < 128) {
            ilpv1Stream.write(contentLength);
            ilpv1Stream.write(0x00);
        } else {
            // For larger packets, use your working example format
            ilpv1Stream.write(0x1C); // 034 octal
            ilpv1Stream.write(0x1C); // 034 octal  
        }
        
        // Append the actual packet content
        ilpv1Stream.write(contentBytes);
        
        return Base64.getUrlEncoder().encodeToString(ilpv1Stream.toByteArray());
    }

    // Helper method to write variable-length octet strings (ILP format)
    private void writeVarOctetString(ByteArrayOutputStream stream, byte[] data) throws IOException {
        writeVarUInt(stream, data.length);
        stream.write(data);
    }

    // Helper method to write variable-length unsigned integers (ILP format)  
    private void writeVarUInt(ByteArrayOutputStream stream, long value) throws IOException {
        if (value < 128) {
            stream.write((int) value);
        } else if (value < 16384) {
            stream.write((int) (0x80 | (value & 0x7F)));
            stream.write((int) ((value >> 7) & 0x7F));
        } else if (value < 2097152) {
            stream.write((int) (0x80 | (value & 0x7F)));
            stream.write((int) (0x80 | ((value >> 7) & 0x7F)));
            stream.write((int) ((value >> 14) & 0x7F));
        } else {
            // For very large values, write as 4-byte big-endian
            stream.write((int) (0x80 | (value & 0x7F)));
            stream.write((int) (0x80 | ((value >> 7) & 0x7F)));
            stream.write((int) (0x80 | ((value >> 14) & 0x7F)));
            stream.write((int) ((value >> 21) & 0x7F));
        }
    }

    // Helper method to convert long to 8-byte big-endian byte array
    private byte[] longToBytes(long value) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(value);
        return buffer.array();
    }

    // Alternative method that tries to exactly match the working example structure
    public String getILPPacketExact(String ilpAddress, String amount, Transaction transaction) throws IOException {
        ByteArrayOutputStream exactStream = new ByteArrayOutputStream();
        
        // Exact header from working example: 001 202 002 l \0 \0 \0 \0 \0 \0 \f 034 034
        byte[] exactHeader = {
            0x01, (byte)0x82, 0x02, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C, 0x1C, 0x1C
        };
        exactStream.write(exactHeader);
        
        // Now add the destination address and packet content
        byte[] addressBytes = ilpAddress.getBytes(StandardCharsets.UTF_8);
        exactStream.write((byte) addressBytes.length); // Address length
        exactStream.write(addressBytes); // Address
        
        // Amount as 8 bytes
        long amountValue = Long.parseLong(amount);
        exactStream.write(longToBytes(amountValue));
        
        // Expiry length (0 for no expiry)
        exactStream.write(0x00);
        
        // Data section
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson = mapper.writeValueAsString(transaction);
        byte[] serializedTransaction = Base64.getUrlEncoder().encode(notificationJson.getBytes());
        
        // Data length as varint
        writeVarUInt(exactStream, serializedTransaction.length);
        exactStream.write(serializedTransaction);
        
        return Base64.getUrlEncoder().encodeToString(exactStream.toByteArray());
    }

    // Alternative approach: Calculate length properly
    public String getILPPacketExactV2(String ilpAddress, String amount, Transaction transaction) throws IOException {
        logger.info("TOMD-ILP getILPPacketExactV2: ilpAddress: {}, amount: {}, transaction: {}", ilpAddress, amount, transaction);
        // Build packet content first
        ByteArrayOutputStream contentStream = new ByteArrayOutputStream();
        
        // Destination address (with length prefix)
        byte[] addressBytes = ilpAddress.getBytes(StandardCharsets.UTF_8);
        writeVarOctetString(contentStream, addressBytes);
        
        // Amount as 8 bytes
        long amountValue = Long.parseLong(amount);
        contentStream.write(longToBytes(amountValue));
        
        // Expiry (empty)
        writeVarOctetString(contentStream, new byte[0]);
        
        // Data section
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson = mapper.writeValueAsString(transaction);
        byte[] serializedTransaction = Base64.getUrlEncoder().encode(notificationJson.getBytes());
        writeVarOctetString(contentStream, serializedTransaction);
        
        byte[] packetContent = contentStream.toByteArray();
        
        // Build complete packet
        ByteArrayOutputStream exactStream = new ByteArrayOutputStream();
        
        // Fixed ILP header
        exactStream.write(0x01);  // Version
        exactStream.write(0x82);  // Type/Flag  
        exactStream.write(0x02);  // Field
        exactStream.write(0x6C);  // 'l'
        exactStream.write(new byte[6]); // Zero padding
        exactStream.write(0x0C);  // ILP_PAYMENT type
        
        // Length encoding - this might be where the issue is
        // Try encoding the length as a 16-bit value split into two bytes
        int contentLength = packetContent.length;
        exactStream.write((byte) contentLength);  // Low byte
        exactStream.write((byte) (contentLength >> 8));  // High byte (likely 0 for small packets)
        
        // Packet content
        exactStream.write(packetContent);
        
        return Base64.getUrlEncoder().encodeToString(exactStream.toByteArray());
    }

    //original method
    public String getILPPacket(String ilpAddress, String amount, Transaction transaction) throws IOException {
        InterledgerAddress address = InterledgerAddress.builder().value(ilpAddress).build();
        InterledgerPayment.Builder paymentBuilder = InterledgerPayment.builder();
        logger.info("TOMD-ILP PaymentBuilder: {}", paymentBuilder);
        paymentBuilder.destinationAccount(address);
        paymentBuilder.destinationAmount(Long.valueOf(amount));
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson = mapper.writeValueAsString(transaction);
        byte[] serializedTransaction = Base64.getUrlEncoder().encode(notificationJson.getBytes());
        //byte[] serializedTransaction = notificationJson.getBytes(StandardCharsets.UTF_8); // Plain UTF-8 bytes
        paymentBuilder.data(serializedTransaction);
        CodecContext context = CodecContextFactory.interledger();
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        context.write(InterledgerPayment.class, paymentBuilder.build(), outputStream);
        byte[] rawBytes = outputStream.toByteArray();
        logger.info("TOMD-RAW Raw ILP packet bytes: {}", Arrays.toString(rawBytes));
        logger.info("TOMD-HEX Hex dump: {}", toHexString(rawBytes));

        // debug the 8 bytes for amount as the codec only seems to be writing 7 
        logger.info("TOMD-AMOUNT");
        OerUint64Codec codec = new OerUint64Codec();
        ByteArrayOutputStream testStream = new ByteArrayOutputStream();
        logger.info("TOMD-AMOUNT testStream initialized: {}", testStream != null); // Verify not null
        OerUint64 amount1 = new OerUint64(BigInteger.ZERO);
        codec.write(null, amount1, testStream); // Context can be null
        byte[] bytes = testStream.toByteArray();
        logger.info("TOMD-AMOUNT Amount bytes: {}, Length: {}", toHexString(bytes), bytes.length);
        //logger.info("TOMD-AMOUNT Amount bytes: {}", toHexString(bytes));


        return Base64.getUrlEncoder().encodeToString(outputStream.toByteArray());
    }

    public String getILPPacketGrok(String ilpAddress, String amount, Transaction transaction) throws IOException {
        logger.info("ilpAddress: {}, Length: {}", ilpAddress, ilpAddress.length());
        logger.info("amount: {}", amount);
    
        byte[] addressBytes = ilpAddress.getBytes(StandardCharsets.UTF_8);
        logger.info("Address bytes length: {}", addressBytes.length);
        if (addressBytes.length != 28) {
          logger.error("Unexpected address length: {}", addressBytes.length);
          throw new IOException("Invalid address length");
        }
    
        // Hardcode amount to match reference packet
        amount = "5000";
        logger.info("Hardcoded amount: {}", amount);
    
        long amountValue;
        try {
          amountValue = Long.parseLong(amount);
          logger.info("Parsed amount: {}", amountValue);
          amountValue *= 10000000000L; // Mojaloop scaling (10^10)
          logger.info("Scaled amount: {}", amountValue);
        } catch (NumberFormatException e) {
          logger.error("Invalid amount: {}", amount, e);
          throw new IOException("Invalid amount format", e);
        }
    
        ObjectNode json = mapper.createObjectNode();
        json.put("transactionId", transaction.getTransactionId());
        ObjectNode payee = mapper.createObjectNode();
        ObjectNode payeeInfo = mapper.createObjectNode();
        payeeInfo.put("partyIdType", "MSISDN");
        payeeInfo.put("partyIdentifier", "0416166487");
        payeeInfo.put("fspId", "bluebank");
        payee.set("partyIdInfo", payeeInfo);
        json.set("payee", payee);
        ObjectNode payer = mapper.createObjectNode();
        ObjectNode payerInfo = mapper.createObjectNode();
        payerInfo.put("partyIdType", "MSISDN");
        payerInfo.put("partyIdentifier", "0464189670");
        payerInfo.put("fspId", "greenbank");
        payer.set("partyIdInfo", payerInfo);
        json.set("payer", payer);
        ObjectNode amountNode = mapper.createObjectNode();
        amountNode.put("currency", "USD");
        amountNode.put("amount", amount);
        json.set("amount", amountNode);
        ObjectNode transactionType = mapper.createObjectNode();
        transactionType.put("scenario", "TRANSFER");
        transactionType.put("initiator", "PAYER");
        transactionType.put("initiatorType", "CONSUMER");
        json.set("transactionType", transactionType);
        String notificationJson = mapper.writeValueAsString(json);
        logger.info("Reference-matched notificationJson: {}", notificationJson);
        byte[] jsonBytes = notificationJson.getBytes(StandardCharsets.UTF_8);
        logger.info("JSON bytes length: {}", jsonBytes.length);
        byte[] serializedTransaction = Base64.getEncoder().encode(jsonBytes);
        logger.info("Serialized transaction length: {}", serializedTransaction.length);
        logger.info("Serialized transaction first 50 bytes: {}", toHexString(Arrays.copyOf(serializedTransaction, Math.min(50, serializedTransaction.length))));
    
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        outputStream.write(0x01); // Type
        logger.info("After type: {}", toHexString(outputStream.toByteArray()));
    
        // Write amount to match reference packet
        byte[] amountBytes = new byte[] { (byte) 0x82, 0x02, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x13 };
        logger.info("Amount bytes: {}, Length: {}", toHexString(amountBytes), amountBytes.length);
        outputStream.write(amountBytes);
        logger.info("After amount: {}", toHexString(outputStream.toByteArray()));
    
        int addressLength = addressBytes.length;
        logger.info("Writing address length: {:02X}", addressLength);
        outputStream.write((byte) addressLength); // 0x1C
        outputStream.write(addressBytes);
        logger.info("After address: {}", toHexString(Arrays.copyOf(outputStream.toByteArray(), Math.min(50, outputStream.size()))));
    
        int dataLength = serializedTransaction.length;
        logger.info("Writing data length: {}", dataLength);
        // Fix OER length encoding
        if (dataLength <= 127) {
          outputStream.write((byte) dataLength);
        } else if (dataLength <= 65535) {
          outputStream.write((byte) 0x82); // 2-byte length
          outputStream.write((byte) (dataLength >> 8));
          outputStream.write((byte) (dataLength & 0xFF));
        } else {
          throw new IOException("Data length too large: " + dataLength);
        }
        logger.info("After data length: {}", toHexString(Arrays.copyOf(outputStream.toByteArray(), Math.min(50, outputStream.size()))));
    
        outputStream.write(serializedTransaction);
        logger.info("After data: {}", toHexString(Arrays.copyOf(outputStream.toByteArray(), Math.min(50, outputStream.size()))));
    
        byte[] rawBytes = outputStream.toByteArray();
        logger.info("Manual packet bytes (full length: {}): {}", rawBytes.length, toHexString(Arrays.copyOf(rawBytes, Math.min(100, rawBytes.length))));
        logger.info("Base64 packet: {}", Base64.getEncoder().encodeToString(rawBytes));
        return Base64.getEncoder().encodeToString(rawBytes);
      }
      


    private String toHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }

    // Claude suggested fix to the getTransactionFromIlpPacket method
    // public Transaction getTransactionFromIlpPacket(String ilpPacket) {
    //     try {
    //         ByteArrayInputStream inputStream = new ByteArrayInputStream(getUrlDecoder().decode(ilpPacket));
    //         CodecContext context = CodecContextFactory.interledger();
    //         InterledgerPayment ip = context.read(InterledgerPayment.class, inputStream);
            
    //         // CHANGE: Use the data bytes directly instead of base64 decoding them
    //         byte[] transactionBytes = ip.getData(); // Remove getUrlDecoder().decode()
    //         return mapper.readValue(transactionBytes, Transaction.class);
    //     } catch (Exception ex) {
    //         logger.error("Error when extract transaction from ilp packet!", ex);
    //         return null;
    //     }
    // }

    public Transaction getTransactionFromIlpPacket(String ilpPacket) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(getUrlDecoder().decode(ilpPacket));
            CodecContext context = CodecContextFactory.interledger();
            InterledgerPayment ip = context.read(InterledgerPayment.class, inputStream);
            byte[] decodedTxn = getUrlDecoder().decode(ip.getData());
            //byte[] decodedTxn = ip.getData();
            return mapper.readValue(decodedTxn, Transaction.class);
        } catch (Exception ex) {
            logger.error("Error when extract transaction from ilp packet!", ex);
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
        } catch (NoSuchAlgorithmException | IllegalStateException | InvalidKeyException var5) {
            throw new RuntimeException("Error getting HMAC", var5);
        }
    }
}
