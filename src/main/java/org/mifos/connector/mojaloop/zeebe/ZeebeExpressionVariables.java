package org.mifos.connector.mojaloop.zeebe;

public class ZeebeExpressionVariables {

    private ZeebeExpressionVariables() {}

    public static final String AUTH_VALIDATION_SUCCESS = "authValidationSuccess";
    public static final String AUTH_RETRIES_LEFT_COUNT = "authRetriesLeftCount";
    public static final String IS_AUTHORISATION_REQUIRED = "isAuthorisationRequired";
    public static final String PARTY_LOOKUP_FAILED = "partyLookupFailed";
    public static final String PARTY_LOOKUP_RETRY_COUNT = "partyLookupRetryCount";
    public static final String PAYER_AUTHORISATION_RETRY_COUNT = "payerAuthorisationRetryCount";
    public static final String PAYER_CONFIRMATION_RETRY_COUNT = "payerConfirmationRetryCount";
    public static final String PAYER_CONFIRMED = "payerConfirmed";
    public static final String QUOTE_FAILED = "quoteFailed";
    public static final String TIMEOUT_QUOTE_RETRY_COUNT = "quoteRetryCount";
    public static final String TIMEOUT_TRANSFER_RETRY_COUNT = "transferRetryCount";
    public static final String TRANSACTION_REQUEST_FAILED = "transactionRequestFailed";
    public static final String TRANSACTION_REQUEST_RETRY_COUNT = "transactionRequestRetryCount";
    public static final String TRANSACTION_STATE = "transactionState";
    public static final String TRANSFER_FAILED = "transferFailed";
    public static final String TRANSFER_STATE = "transferState";

}