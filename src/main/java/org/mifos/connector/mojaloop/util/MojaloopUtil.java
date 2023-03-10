package org.mifos.connector.mojaloop.util;

import com.ilp.conditions.models.pdp.Transaction;
import org.apache.camel.Exchange;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.PARTIES_ACCEPT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.PARTIES_CONTENT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.QUOTES_ACCEPT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.QUOTES_CONTENT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.TRANSACTIONS_ACCEPT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.TRANSACTIONS_CONTENT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.TRANSFERS_ACCEPT_TYPE;
import static org.mifos.connector.common.mojaloop.type.InteroperabilityType.TRANSFERS_CONTENT_TYPE;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.*;

@Component
public class MojaloopUtil {

    @Value("${switch.account-lookup-service}")
    private String accountLookupService;

    @Value("${switch.quote-service}")
    private String switchQuoteService;

    @Value("${switch.transfer-service}")
    private String transferService;

    @Value("${switch.transaction-request-service}")
    private String transactionRequestService;

    public void setPartyHeadersResponse(Exchange exchange) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
        headers.put(FSPIOP_DESTINATION.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
        headers.put(HEADER_CONTENT_TYPE, PARTIES_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, PARTIES_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, accountLookupService);
        setResponseTraceHeaders(exchange, headers);
        finalizeHeaders(exchange, headers);
    }

    public void setPartyHeadersRequest(Exchange exchange) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), exchange.getIn().getHeader(FSPIOP_SOURCE.headerName()));
        headers.put(HEADER_CONTENT_TYPE, PARTIES_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, PARTIES_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, accountLookupService);
        finalizeHeaders(exchange, headers);
    }

    public void setQuoteHeadersResponse(Exchange e, QuoteSwitchRequestDTO request) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), request.getPayee().getPartyIdInfo().getFspId());
        headers.put(FSPIOP_DESTINATION.headerName(), request.getPayer().getPartyIdInfo().getFspId());
        headers.put(HEADER_CONTENT_TYPE, QUOTES_CONTENT_TYPE.headerValue());
        headers.put(HEADER_HOST, switchQuoteService);
        setResponseTraceHeaders(e, headers);
        finalizeHeaders(e, headers);
    }

    public void setQuoteHeadersRequest(Exchange e) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), e.getProperty(FSPIOP_SOURCE.headerName()));
        headers.put(FSPIOP_DESTINATION.headerName(), e.getProperty(FSPIOP_DESTINATION.headerName()));
        headers.put(HEADER_CONTENT_TYPE, QUOTES_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, QUOTES_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, switchQuoteService);
        finalizeHeaders(e, headers);
    }

    public void setTransferHeadersResponse(Exchange e, Transaction transaction) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), transaction.getPayee().getPartyIdInfo().getFspId());
        headers.put(FSPIOP_DESTINATION.headerName(), transaction.getPayer().getPartyIdInfo().getFspId());
        headers.put(HEADER_CONTENT_TYPE, TRANSFERS_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, TRANSFERS_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, transferService);
        setResponseTraceHeaders(e, headers);
        finalizeHeaders(e, headers);
    }

    public void setTransferHeadersRequest(Exchange e, Transaction transaction) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), transaction.getPayer().getPartyIdInfo().getFspId());
        headers.put(FSPIOP_DESTINATION.headerName(), transaction.getPayee().getPartyIdInfo().getFspId());
        headers.put(HEADER_CONTENT_TYPE, TRANSFERS_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, TRANSFERS_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, transferService);
        finalizeHeaders(e, headers);
    }

    public void setTransactionRequestHeadersResponse(Exchange e) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), e.getProperty(FSPIOP_SOURCE.headerName()));
        headers.put(FSPIOP_DESTINATION.headerName(), e.getProperty(FSPIOP_DESTINATION.headerName()));
        headers.put(HEADER_CONTENT_TYPE, TRANSACTIONS_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, TRANSACTIONS_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, transactionRequestService);
        setResponseTraceHeaders(e, headers);
        finalizeHeaders(e, headers);
    }

    public void setTransactionRequestHeadersRequest(Exchange e) {
        Map<String, Object> headers = new HashMap<>();
        headers.put(FSPIOP_SOURCE.headerName(), e.getProperty(FSPIOP_SOURCE.headerName()));
        headers.put(FSPIOP_DESTINATION.headerName(), e.getProperty(FSPIOP_DESTINATION.headerName()));
        headers.put(HEADER_CONTENT_TYPE, TRANSACTIONS_CONTENT_TYPE.headerValue());
        headers.put(HEADER_ACCEPT, TRANSACTIONS_ACCEPT_TYPE.headerValue());
        headers.put(HEADER_HOST, transactionRequestService);
        finalizeHeaders(e, headers);
    }

    private void finalizeHeaders(Exchange e, Map<String, Object> headers) {
        e.getIn().removeHeaders("*");
        e.getIn().setHeaders(headers);
    }

    private void setResponseTraceHeaders(Exchange exchange, Map<String, Object> headers) {
        headers.put(HEADER_DATE, exchange.getIn().getHeader(HEADER_DATE));
        headers.put(HEADER_TRACEPARENT, exchange.getIn().getHeader(HEADER_TRACEPARENT));
        Object tracestate = exchange.getIn().getHeader(HEADER_TRACESTATE);
        if (tracestate != null) {
            headers.put(HEADER_TRACESTATE, tracestate);
        }
    }
}
