package org.mifos.connector.mojaloop.camel.config;

public class CamelProperties {

    private CamelProperties() {}

    public static final String CACHED_TRANSACTION_ID = "CACHED_TRANSACTION_ID";

    public static final String ORIGIN_DATE = "originDate";
    public static final String TRANSACTION_ID = "transactionId";
    public static final String TRANSACTION_REQUEST = "transactionRequest";

    public static final String PAYEE_PARTY_ID_TYPE = "payeePartyIdType";
    public static final String PAYEE_PARTY_IDENTIFIER = "payeePartyIdentifier";
    public static final String PAYEE_FSP_ID = "payeeFspId";

    public static final String PAYER_PARTY_ID_TYPE = "payerPartyIdType";
    public static final String PAYER_PARTY_IDENTIFIER = "payerPartyIdentifier";
    public static final String PAYER_FSP_ID = "payerFspId";

    public static final String TIMEOUT_QUOTE_RETRY_COUNT = "timeoutQuoteRetryCount";
    public static final String TIMEOUT_TRANSFER_RETRY_COUNT = "timeoutTransferRetryCount";

}
