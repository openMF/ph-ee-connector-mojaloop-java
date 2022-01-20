/*
 * This Source Code Form is subject to the terms of the Mozilla
 * Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at
 *
 *  https://mozilla.org/MPL/2.0/.
 */
package org.mifos.connector.mojaloop.ilp;

import com.ilp.conditions.models.pdp.*;
import org.mifos.connector.common.mojaloop.dto.ComplexName;
import org.mifos.connector.common.mojaloop.dto.Party;
import org.mifos.connector.common.mojaloop.dto.PersonalInfo;
import org.mifos.connector.common.util.ContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.beans.Transient;
import java.io.IOException;
import java.math.BigDecimal;

@Component
public class IlpBuilder {

    private static final String ILP_ADDRESS_TEMPLATE = "g.tz.%s.%s.%s";

    @Autowired
    private IlpConditionHandlerImpl ilpConditionHandlerImpl;

    @Value("${connector.ilp-secret}")
    private String conectorIlpSecret;

    public Ilp build(String transactionId, String quoteId, BigDecimal transactionAmount, String currency, Party payer,
                     Party payee, BigDecimal transferAmount) throws IOException {
        Transaction transaction = mapToTransaction(transactionId, quoteId, transactionAmount, currency, payer, payee);
        return build(transaction, transferAmount);
    }

    public Ilp build(Transaction transaction, BigDecimal amount) throws IOException {
        String ilpAddress = buildIlpAddress(transaction);
        String ilpPacket = ilpConditionHandlerImpl.getILPPacket(ilpAddress, ContextUtil.formatAmount(amount), transaction);
        String ilpCondition = ilpConditionHandlerImpl.generateCondition(ilpPacket, conectorIlpSecret.getBytes());
        String fulfillment = ilpConditionHandlerImpl.generateFulfillment(ilpPacket, conectorIlpSecret.getBytes());

        return new Ilp(ilpPacket, ilpCondition, fulfillment, transaction);
    }

    public Ilp parse(String packet, String condition)  {
        return new Ilp(packet, condition, ilpConditionHandlerImpl.getTransactionFromIlpPacket(packet));
    }

    public boolean isValidPacketAgainstCondition(String packet, String condition) {
        return ilpConditionHandlerImpl.generateCondition(packet, conectorIlpSecret.getBytes()).equals(condition);
    }

    public String buildIlpAddress(Transaction transaction) {
        PartyIdInfo partyIdInfo = transaction.getPayee().getPartyIdInfo();
        return String.format(ILP_ADDRESS_TEMPLATE, partyIdInfo.getFspId(), partyIdInfo.getPartyIdType(), partyIdInfo.getPartyIdentifier());
    }

    private Transaction mapToTransaction(String transactionId, String quoteId, BigDecimal transactionAmount, String currency,
                                         Party payer, Party payee) {
        Money money = new Money();
        money.setAmount(ContextUtil.formatAmount(transactionAmount));
        money.setCurrency(currency);

        return mapToTransaction(transactionId, quoteId, money, payer, payee);
    }

    private Transaction mapToTransaction(String transactionId, String quoteId, Money transactionAmount, Party payer, Party payee) {
        Transaction transaction = new Transaction();

        transaction.setTransactionId(transactionId);
        transaction.setQuoteId(quoteId);
        transaction.setAmount(transactionAmount);
        transaction.setPayer(getIlpPartyFromParty(payer));
        transaction.setPayee(getIlpPartyFromParty(payee));

        return transaction;
    }

    @Transient
    public com.ilp.conditions.models.pdp.Party getIlpPartyFromParty(Party party) {
        com.ilp.conditions.models.pdp.Party ilpParty = new com.ilp.conditions.models.pdp.Party();
        ilpParty.setMerchantClassificationCode(party.getMerchantClassificationCode());
        ilpParty.setName(party.getName());

        org.mifos.connector.common.mojaloop.dto.PartyIdInfo partyIdInfo = party.getPartyIdInfo();
        com.ilp.conditions.models.pdp.PartyIdInfo ilpPartyIdInfo = new com.ilp.conditions.models.pdp.PartyIdInfo();
        ilpPartyIdInfo.setFspId(partyIdInfo.getFspId());
        ilpPartyIdInfo.setPartyIdentifier(partyIdInfo.getPartyIdentifier());
        ilpPartyIdInfo.setPartyIdType(partyIdInfo.getPartyIdType().name());
        ilpPartyIdInfo.setPartySubIdOrType(partyIdInfo.getPartySubIdOrType());
        ilpParty.setPartyIdInfo(ilpPartyIdInfo);

        PersonalInfo personalInfo = party.getPersonalInfo();
        if (personalInfo != null) {
            PartyPersonalInfo ilpPersonalInfo = new PartyPersonalInfo();
            ilpPersonalInfo.setDateOfBirth(personalInfo.getDateOfBirth());
            PartyComplexName payerComplexName = new PartyComplexName();
            ComplexName complexName = personalInfo.getComplexName();
            payerComplexName.setFirstName(complexName.getFirstName());
            payerComplexName.setLastName(complexName.getLastName());
            payerComplexName.setMiddleName(complexName.getMiddleName());
            ilpPersonalInfo.setComplexName(payerComplexName);
            ilpParty.setPersonalInfo(ilpPersonalInfo);
        }

        return ilpParty;
    }
}