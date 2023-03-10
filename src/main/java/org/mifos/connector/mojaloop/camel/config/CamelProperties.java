package org.mifos.connector.mojaloop.camel.config;

public class CamelProperties {

    private CamelProperties() {}

    public static final String AUTH_TYPE = "authType";
    public static final String CACHED_TRANSACTION_ID = "cachedTransactionId";
    public static final String PARTY_EXISTS = "partyExists";

    public static final String HOST = "externalApiCallHost";
    public static final String ENDPOINT = "externalApiCallEndpoint";

    public static final String CUSTOM_HEADER_FILTER_STRATEGY = "customHeaderFilterStrategy";

    public static final String CLASS_TYPE = "classType";

    public static final String HEADER_CONTENT_TYPE = "Content-Type";

    public static final String HEADER_ACCEPT = "Accept";

    public static final String HEADER_HOST = "Host";

    public static final String HEADER_DATE = "Date";

    public static final String HEADER_TRACEPARENT = "traceparent";

    public static final String HEADER_TRACESTATE = "tracestate";

    public static final String HEADER_VALUE_TYPE_JSON = "application/json";



}
