package org.mifos.connector.mojaloop.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayeeFspCommission{
    private String amount;
    private String currency;
}
