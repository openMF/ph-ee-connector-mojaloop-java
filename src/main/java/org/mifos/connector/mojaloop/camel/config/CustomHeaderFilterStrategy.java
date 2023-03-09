package org.mifos.connector.mojaloop.camel.config;

import org.apache.camel.support.DefaultHeaderFilterStrategy;

public class CustomHeaderFilterStrategy extends DefaultHeaderFilterStrategy {

    public CustomHeaderFilterStrategy() {
        initialize();
    }

    protected void initialize() {
        getOutFilter().add("content-length");
        //getOutFilter().add("content-type");
        getOutFilter().add("host");
        // Add the filter for the Generic Message header
        // http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html#sec4.5
        getOutFilter().add("cache-control");
        getOutFilter().add("connection");
        //getOutFilter().add("date");
        getOutFilter().add("pragma");
        getOutFilter().add("trailer");
        getOutFilter().add("transfer-encoding");
        getOutFilter().add("upgrade");
        getOutFilter().add("via");
        getOutFilter().add("warning");

        setLowerCase(true);

        // filter headers begin with "Camel" or "org.apache.camel"
        // must ignore case for Http based transports
        setOutFilterStartsWith(CAMEL_FILTER_STARTS_WITH);
        setInFilterStartsWith(CAMEL_FILTER_STARTS_WITH);
    }
}
