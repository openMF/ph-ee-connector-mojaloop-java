package org.mifos.connector.mojaloop.model;

import lombok.Getter;
import lombok.Setter;
import org.mifos.connector.common.mojaloop.dto.MoneyData;

@Getter
@Setter
public class QuoteCallbackDTO {

    private MoneyData transferAmount;
    private MoneyData payeeFspFee;
    private MoneyData payeeFspCommission;
    private String expiration;
    private String ilpPacket;
    private String condition;
}
