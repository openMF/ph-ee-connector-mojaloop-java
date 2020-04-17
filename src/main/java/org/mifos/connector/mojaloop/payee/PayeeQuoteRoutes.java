package org.mifos.connector.mojaloop.payee;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.mifos.phee.common.ams.dto.QuoteFspResponseDTO;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.mojaloop.dto.FspMoneyData;
import org.mifos.phee.common.mojaloop.dto.MoneyData;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchRequestDTO;
import org.mifos.phee.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.phee.common.mojaloop.ilp.Ilp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static java.math.BigDecimal.ZERO;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.LOCAL_QUOTE_RESPONSE;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_ID;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.QUOTE_SWITCH_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class PayeeQuoteRoutes extends ErrorHandlerRouteBuilder {

    @Value("${bpmn.flows.quote}")
    private String quoteFlow;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ZeebeProcessStarter zeebeProcessStarter;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    public PayeeQuoteRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/quotes")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYEE - forward quote request - STEP 2")
                .setProperty(QUOTE_SWITCH_REQUEST, bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(exchange -> {
                            QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);

                            zeebeProcessStarter.startZeebeWorkflow(quoteFlow, null, variables -> {
                                variables.put(QUOTE_ID, request.getQuoteId());
                                variables.put(FSPIOP_SOURCE.headerName(), request.getPayee().getPartyIdInfo().getFspId());
                                variables.put(FSPIOP_DESTINATION.headerName(), request.getPayer().getPartyIdInfo().getFspId());
                                variables.put(TRANSACTION_ID, request.getTransactionId());
                                variables.put(QUOTE_SWITCH_REQUEST, exchange.getProperty(QUOTE_SWITCH_REQUEST));

                                ZeebeProcessStarter.camelHeadersToZeebeVariables(exchange, variables,
                                        "Date",
                                        "traceparent"
                                );
                            });
                        }
                );

        from("direct:send-quote-error-to-switch")
                .id("send-quote-error-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(e -> {
                    mojaloopUtil.setQuoteHeaders(e, e.getIn().getBody(QuoteSwitchRequestDTO.class));
                    e.getIn().setBody(e.getProperty(ERROR_INFORMATION));
                })
                .toD("rest:PUT:/quotes/${header."+QUOTE_ID+"}/error?host={{switch.host}}");

        from("direct:send-quote-to-switch")
                .id("send-quote-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchRequestDTO.class)
                .process(exchange -> {
                    QuoteSwitchRequestDTO request = exchange.getIn().getBody(QuoteSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.build(request.getTransactionId(),
                            request.getQuoteId(),
                            request.getAmount().getAmountDecimal(),
                            request.getAmount().getCurrency(),
                            request.getPayer(),
                            request.getPayee(),
                            request.getAmount().getAmountDecimal());

                    QuoteFspResponseDTO localQuoteResponse = objectMapper.readValue(exchange.getIn().getHeader(LOCAL_QUOTE_RESPONSE, String.class), QuoteFspResponseDTO.class);
                    FspMoneyData fspFee = localQuoteResponse.getFspFee();
                    FspMoneyData fspCommission = localQuoteResponse.getFspCommission();

                    // amount format: ^([0]|([1-9][0-9]{0,17}))([.][0-9]{0,3}[1-9])?$
                    BigDecimal fspFeeAmount = (fspFee != null ? fspFee.getAmount() : ZERO).stripTrailingZeros();
                    String fspFeeCurrency = fspFee != null ? fspFee.getCurrency() : request.getAmount().getCurrency();
                    BigDecimal fspCommissionAmount = (fspCommission != null ? fspCommission.getAmount() : ZERO).stripTrailingZeros();
                    String fspCommissionCurrency = fspCommission != null ? fspCommission.getCurrency() : request.getAmount().getCurrency();

                    QuoteSwitchResponseDTO response = new QuoteSwitchResponseDTO(
                            request.getAmount(),
                            new MoneyData(request.getAmount().getAmountDecimal().subtract(fspFeeAmount).subtract(fspCommissionAmount).stripTrailingZeros().toPlainString(),
                                    request.getAmount().getCurrency()),
                            new MoneyData(fspFeeAmount.compareTo(ZERO) == 0 ? "0" : fspFeeAmount.toPlainString(), fspFeeCurrency),
                            new MoneyData(fspCommissionAmount.compareTo(ZERO) == 0 ? "0" : fspCommissionAmount.toPlainString(), fspCommissionCurrency),
                            LocalDateTime.now().plusHours(1),
                            null,
                            ilp.getPacket(),
                            ilp.getCondition(),
                            null
                    );

                    mojaloopUtil.setQuoteHeaders(exchange, request);
                    exchange.getIn().setBody(response);
                })
                .process(pojoToString)
                .toD("rest:PUT:/quotes/${header."+QUOTE_ID+"}?host={{switch.host}}");
    }
}
