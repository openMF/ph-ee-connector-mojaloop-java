package org.mifos.connector.mojaloop.camel.trace;


import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.common.util.ContextUtil;
import org.springframework.stereotype.Component;

import java.util.Base64;

import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ORIGIN_DATE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;

@Component
public class AddTraceHeaderProcessor implements Processor {

    /**
     * Adds Date and traceparent headers for every outgoing request to mojaloop.
     * Date: https://tools.ietf.org/html/rfc7231#section-7.1.1.1
     * traceparent: https://w3c.github.io/trace-context/
     * format: "version"-"trace-id"-"parent-id"-"trace-flags"'
     * <p>
     * Mojaloop does not support correlatinId at every async request-response in a common way so we are using this 'traceparent' header
     */
    @Override
    public void process(Exchange exchange) {
        String transactionId = exchange.getProperty(TRANSACTION_ID, String.class);
        String transactionIdTrimed = transactionId.replace("-", "");
        String transactionIdKey = transactionIdTrimed.substring(0, 16);
        String traceParent = String.join("-", "00", transactionIdTrimed, transactionIdKey, "01");

        exchange.getIn().setHeader("traceparent", traceParent);
        exchange.getIn().setHeader("tracestate", "ph=" + Base64.getEncoder().encodeToString(transactionIdKey.getBytes()));
        exchange.getIn().setHeader("Date",
                ContextUtil.formatToDateHeader(exchange.getProperty(ORIGIN_DATE, Long.class)));
    }
}
