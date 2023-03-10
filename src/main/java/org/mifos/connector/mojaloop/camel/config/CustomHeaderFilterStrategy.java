package org.mifos.connector.mojaloop.camel.config;

import org.apache.camel.support.DefaultHeaderFilterStrategy;

public class CustomHeaderFilterStrategy extends DefaultHeaderFilterStrategy {

    public CustomHeaderFilterStrategy() {
        initialize();
    }

    protected void initialize() {
        getOutFilter().add("content-length");
        getOutFilter().add("host");
        getOutFilter().add("cache-control");
        getOutFilter().add("connection");
        getOutFilter().add("pragma");
        getOutFilter().add("trailer");
        getOutFilter().add("transfer-encoding");
        getOutFilter().add("upgrade");
        getOutFilter().add("via");
        getOutFilter().add("warning");
        setLowerCase(true);
        setOutFilterStartsWith(CAMEL_FILTER_STARTS_WITH);
        setInFilterStartsWith(CAMEL_FILTER_STARTS_WITH);
    }
}
