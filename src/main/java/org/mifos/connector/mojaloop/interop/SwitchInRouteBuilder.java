package org.mifos.connector.mojaloop.interop;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.mojaloop.dto.PartySwitchResponseDTO;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.mifos.connector.mojaloop.camel.trace.GetCachedTransactionIdProcessor;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.payer.PartiesResponseProcessor;
import org.mifos.connector.mojaloop.payer.QuoteResponseProcessor;
import org.mifos.connector.mojaloop.payer.TransferResponseProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


@Component
public class SwitchInRouteBuilder extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private GetCachedTransactionIdProcessor getCachedTransactionIdProcessor;

    @Autowired
    private PartiesResponseProcessor partiesResponseProcessor;

    @Autowired
    private TransferResponseProcessor transferResponseProcessor;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private QuoteResponseProcessor quoteResponseProcessor;

    public SwitchInRouteBuilder() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:PUT:/switch/parties/MSISDN/{phone}")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYER - response for parties request  - STEP 3")
                .unmarshal().json(JsonLibrary.Jackson, PartySwitchResponseDTO.class)
                .process(getCachedTransactionIdProcessor)
                .process(partiesResponseProcessor);

        from("rest:PUT:/switch/quotes/{qid}")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYER - response for quote request - STEP 3")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchResponseDTO.class)
                .process(getCachedTransactionIdProcessor)
                .process(quoteResponseProcessor);

        from("rest:PUT:/switch/transfers/{tid}")
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchResponseDTO.class)
                .process(getCachedTransactionIdProcessor)
                .process(transferResponseProcessor);

        // ERROR callback urls
        from("rest:PUT:/switch/quotes/{qid}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYEE/PAYER - quote error")
                .process(e -> logger.error(e.getIn().getBody(String.class)));

        from("rest:PUT:/switch/parties/MSISDN/{phone}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYEE/PAYER - parties error")
                .process(e -> logger.error(e.getIn().getBody(String.class)));
    }
}