package org.mifos.connector.mojaloop.zeebe;

public class ZeebeExpressionVariables {

    private ZeebeExpressionVariables() {}

    public static final String PAYER_CONFIRMED = "payerConfirmed";
    public static final String PARTY_LOOKUP_FAILED = "partyLookupFailed";
    public static final String QUOTE_FAILED = "quoteFailed";
    public static final String TRANSFER_FAILED = "transferFailed";
    public static final String TRANSFER_STATE = "transferState";
    public static final String TIMEOUT_PARTY_LOOKUP_COUNT = "partyLookupRetryCount";
    public static final String TIMEOUT_QUOTE_RETRY_COUNT = "quoteRetryCount";
    public static final String TIMEOUT_TRANSFER_RETRY_COUNT = "transferRetryCount";
}