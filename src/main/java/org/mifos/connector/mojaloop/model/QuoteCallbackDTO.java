package org.mifos.connector.mojaloop.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuoteCallbackDTO {
    private TransferAmount transferAmount;
    private PayeeFspFee payeeFspFee;
    private PayeeFspCommission payeeFspCommission;
    private String expiration;
    private String ilpPacket;
    private String condition;
}
