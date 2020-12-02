package org.mifos.connector.mojaloop.zeebe;

public class ZeebeMessages {

    private ZeebeMessages() {}

    public static final String ACCEPT_QUOTE = "accept-quote";
    public static final String AUTHORISATION_REQUEST = "authorisation-request";
    public static final String PARTY_LOOKUP = "party-lookup";
    public static final String PAYER_AUTH_CONFIRMATION = "payer-auth-confirmation";
    public static final String PAYER_AUTH_RESPONSE = "payer-auth-response";
    public static final String PAYER_CONFIRMATION = "payer-confirmation";
    public static final String QUOTE_CALLBACK = "quote-callback";
    public static final String QUOTE_ERROR = "quote-error";
    public static final String TRANSFER_MESSAGE = "transfer-message";
    public static final String TRANSFER_RESPONSE = "transfer-response";
    public static final String TRANSACTION_REQUEST = "transaction-request";

}
