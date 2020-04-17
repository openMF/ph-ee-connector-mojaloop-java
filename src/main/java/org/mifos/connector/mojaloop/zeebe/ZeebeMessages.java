package org.mifos.connector.mojaloop.zeebe;

public class ZeebeMessages {

    private ZeebeMessages() {}

    public static final String ACCEPT_QUOTE = "accept-quote";
    public static final String PAYEE_USER_LOOKUP = "payee-user-lookup";
    public static final String QUOTE = "quote";
    public static final String TRANSFER_MESSAGE = "TransferMessage-DFSPID";
    public static final String TRANSFER_RESPONSE = "transfer-response";

}
