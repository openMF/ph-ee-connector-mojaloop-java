package org.mifos.connector.mojaloop.camel.trace;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.UUID;

@Component
public class GetCachedTransactionIdProcessor implements Processor {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void process(Exchange exchange) {
        String transactionIdKey = null;
        String traceparent = exchange.getIn().getHeader("traceparent", String.class);
        logger.info("trace parent header: {}", traceparent);

        String transactionId = resolveTransactionIdFromTraceparent(traceparent);

        logger.info("resolved parent {} to transactionId {}", traceparent, transactionId);
        exchange.setProperty(CamelProperties.CACHED_TRANSACTION_ID, transactionId);

        if (transactionId == null) {
            logger.error("FATAL: failed to resolve transactionIdKey {} in local TransactionCache, stopping route now", transactionIdKey);
            exchange.setRouteStop(true);
        }
    }

    public static String resolveTransactionIdFromTraceparent(String traceparent) {
        String parts = traceparent.split("-")[1];
        Long most = new BigInteger(parts.substring(0, 16), 16).longValue();
        Long least = new BigInteger(parts.substring(16, 32), 16).longValue();
        return new UUID(most, least).toString();
    }
}
