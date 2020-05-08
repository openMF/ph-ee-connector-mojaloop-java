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
import com.ilp.conditions.models.pdp.Transaction;
import org.interledger.Condition;
import org.interledger.Fulfillment;
import org.interledger.InterledgerAddress;
import org.interledger.codecs.CodecContext;
import org.mifos.connector.common.mojaloop.ilp.CodecContextFactory;
import org.mifos.connector.common.mojaloop.ilp.InterledgerPayment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static java.util.Base64.getUrlDecoder;


@Component
public class IlpConditionHandlerImpl {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Autowired
    private ObjectMapper mapper;

    public String getILPPacket(String ilpAddress, String amount, Transaction transaction) throws IOException {
        InterledgerAddress address = InterledgerAddress.builder().value(ilpAddress).build();
        InterledgerPayment.Builder paymentBuilder = InterledgerPayment.builder();
        paymentBuilder.destinationAccount(address);
        paymentBuilder.destinationAmount(amount);
        mapper.setSerializationInclusion(Include.NON_NULL);
        String notificationJson = mapper.writeValueAsString(transaction);
        byte[] serializedTransaction = Base64.getUrlEncoder().encode(notificationJson.getBytes());
        paymentBuilder.data(serializedTransaction);
        CodecContext context = CodecContextFactory.interledger();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        context.write(InterledgerPayment.class, paymentBuilder.build(), outputStream);
        return Base64.getUrlEncoder().encodeToString(outputStream.toByteArray());
    }

    public Transaction getTransactionFromIlpPacket(String ilpPacket) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(getUrlDecoder().decode(ilpPacket));
            CodecContext context = CodecContextFactory.interledger();
            InterledgerPayment ip = context.read(InterledgerPayment.class, inputStream);
            byte[] decodedTxn = getUrlDecoder().decode(ip.getData());
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