package org.mifos.connector.mojaloop.camel.trace;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class QuoteTransactionCache {

    private final Map<String, String> quoteTransactionCache = new ConcurrentHashMap<>();

    public void add(String quoteId, String transactionId) {
        quoteTransactionCache.put(quoteId, transactionId);
    }

    public String get(String quoteId) {
        return quoteTransactionCache.get(quoteId);
    }
}
