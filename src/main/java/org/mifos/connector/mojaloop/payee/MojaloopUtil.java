package org.mifos.connector.mojaloop.payee;

import com.ilp.conditions.models.pdp.Transaction;
import org.apache.camel.Exchange;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.PARTIES_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.QUOTES_ACCEPT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.QUOTES_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.InteroperabilityType.TRANSFERS_CONTENT_TYPE;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class MojaloopUtil {

    @Value("${switch.account-lookup-service}")
    private String accountLookupService;

    @Value("${switch.quote-service}")
    private String switchQuoteService;

    @Value("${switch.transfer-service}")
    private String transferService;

    public void setPartyHeaders(Exchange exchange) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
        headers.put(FSPIOP_DESTINATION.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
        headers.put("Content-Type", PARTIES_CONTENT_TYPE.headerValue());
        headers.put("Host", accountLookupService);
        setCommonHeaders(exchange, headers);
    }

    public void setQuoteHeaders(Exchange e, QuoteSwitchRequestDTO request) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(QUOTE_ID, request.getQuoteId());
        headers.put(FSPIOP_SOURCE.headerName(), request.getPayee().getPartyIdInfo().getFspId());
        headers.put(FSPIOP_DESTINATION.headerName(), request.getPayer().getPartyIdInfo().getFspId());
        headers.put("Content-Type", QUOTES_CONTENT_TYPE.headerValue());
        headers.put("Accept", QUOTES_ACCEPT_TYPE.headerValue());
        headers.put("Host", switchQuoteService);
        setCommonHeaders(e, headers);
    }

    public void setTransferHeaders(Exchange e, Transaction transaction) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(TRANSACTION_ID, transaction.getTransactionId());
        headers.put(FSPIOP_SOURCE.headerName(), transaction.getPayee().getPartyIdInfo().getFspId());
        headers.put(FSPIOP_DESTINATION.headerName(), transaction.getPayer().getPartyIdInfo().getFspId());
        headers.put("Content-Type", TRANSFERS_CONTENT_TYPE.headerValue());
        headers.put("Host", transferService);
        setCommonHeaders(e, headers);
    }

    private void setCommonHeaders(Exchange exchange, Map<String, Object> headers) {
        headers.put("Date", exchange.getIn().getHeader("Date"));
        headers.put("traceparent", exchange.getIn().getHeader("traceparent"));
        Object tracestate = exchange.getIn().getHeader("tracestate");
        if (tracestate != null) {
            headers.put("tracestate", tracestate);
        }
        exchange.getIn().removeHeaders("*");
        exchange.getIn().setHeaders(headers);
    }
}
