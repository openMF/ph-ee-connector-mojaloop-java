package org.mifos.connector.mojaloop.camel.config;

public class CamelProperties {

    private CamelProperties() {}

    public static final String CACHED_TRANSACTION_ID = "CACHED_TRANSACTION_ID";
    public static final String LOCAL_QUOTE_RESPONSE = "localQuoteResponse";
    public static final String ORIGIN_DATE = "originDate";
    public static final String PARTY_ID = "partyId";
    public static final String PARTY_ID_TYPE = "partyIdType";
    public static final String PAYEE_FSP_ID = "payeeFspId";
    public static final String PAYEE_PARTY_RESPONSE = "payeePartyResponse";
    public static final String PAYEE_QUOTE_RESPONSE = "payeeQuoteResponse";
    public static final String PAYER_FSP_ID = "payerFspId";
    public static final String SWITCH_TRANSFER_REQUEST = "switchTransferRequest";
    public static final String TENANT_ID = "tenantId";
    public static final String TIMEOUT_QUOTE_RETRY_COUNT = "timeoutQuoteRetryCount";
    public static final String TIMEOUT_TRANSFER_RETRY_COUNT = "timeoutTransferRetryCount";
    public static final String TRANSACTION_ID = "transactionId";
    public static final String TRANSACTION_REQUEST = "transactionRequest";
    public static final String QUOTE_ID = "quoteId";
    public static final String QUOTE_SWITCH_REQUEST = "quoteSwitchRequest";
}
