package org.mifos.connector.mojaloop.ilp;

import com.ilp.conditions.models.pdp.Transaction;

public class Ilp {

    private final String packet; // mandatory
    private final String condition; // mandatory
    private final String fulfilment; // optional
    private final Transaction transaction; // mandatory

    public Ilp(String packet, String condition, String fulfilment, Transaction transaction) {
        this.packet = packet;
        this.condition = condition;
        this.fulfilment = fulfilment;
        this.transaction = transaction;
    }

    public Ilp(String packet, String condition, Transaction transaction) {
        this(packet, condition, null, transaction);
    }

    public String getPacket() {
        return packet;
    }

    public String getCondition() {
        return condition;
    }

    public String getFulfilment() {
        return fulfilment;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    void update(Ilp oIlp) {
        if (oIlp == null)
            return;
        if (!packet.equals(oIlp.getPacket()))
            throw new RuntimeException("Ilp packet is not valid " + packet + " vs." + oIlp.getPacket());
        if (!condition.equals(oIlp.getCondition()))
            throw new RuntimeException("Ilp condition is not valid " + packet + " vs." + oIlp.getPacket());
        if (fulfilment != null && oIlp.getFulfilment() != null && !fulfilment.equals(oIlp.getFulfilment()))
            throw new RuntimeException("Ilp fulfilment is not valid " + fulfilment + " vs." + oIlp.getFulfilment());

    }

    @Override
    public String toString() {
        return "Ilp{" +
                "packet:'" + packet + '\'' +
                ", condition:'" + condition + '\'' +
                ", fulfilment:'" + fulfilment + '\'' +
                '}';
    }
}
