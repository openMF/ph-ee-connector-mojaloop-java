package org.mifos.connector.mojaloop.payee;

import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchRequestDTO;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.mifos.phee.common.mojaloop.ilp.Ilp;
import org.mifos.phee.common.mojaloop.type.TransferState;
import org.mifos.phee.common.util.ContextUtil;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;

import org.mifos.connector.mojaloop.interop.SwitchOutRouteBuilder;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class PayeeTransferRoutes extends ErrorHandlerRouteBuilder {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Value("${switch.transfer-service}")
    private String transferService;

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeClient zeebeClient;

    public PayeeTransferRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/transfers")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYEE - forward transfer request - STEP 2")
                .setProperty("savedBody", bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchRequestDTO.class)
                .process(exchange -> {
                    TransferSwitchRequestDTO request = exchange.getIn().getBody(TransferSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.parse(request.getIlpPacket(), request.getCondition());

                    Map<String, Object> variables = new HashMap<>();
                    variables.put(CamelProperties.TRANSACTION_REQUEST, exchange.getProperty("savedBody"));
                    variables.put("tid", ilp.getTransaction().getTransactionId());
                    variables.put("fspiop-source", request.getPayeeFsp());
                    variables.put("fspiop-destination", request.getPayerFsp());
                    variables.put("Date", exchange.getIn().getHeader("Date"));
                    variables.put("traceparent", exchange.getIn().getHeader("traceparent"));

                    zeebeClient.newPublishMessageCommand()
                            .messageName("TransferMessage-DFSPID")  // TODO externalize
                            .correlationKey(ilp.getTransaction().getTransactionId())
                            .variables(variables)
                            .send();
                });

        from("seda:send-transfer-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchRequestDTO.class)
                .process(exchange -> {
                    TransferSwitchRequestDTO request = exchange.getIn().getBody(TransferSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.parse(request.getIlpPacket(), request.getCondition());

                    TransferSwitchResponseDTO response = new TransferSwitchResponseDTO(
                            ilp.getFulfilment(),
                            ContextUtil.parseMojaDate(exchange.getIn().getHeader("Date", String.class)), // there is a validation at fulfiltransfer: completedTimestamp.getTime() > now.getTime() + maxCallbackTimeLagDilation(200ms by default)
                            TransferState.COMMITTED,
                            null);
                    exchange.getIn().setBody(response);

                    Map<String, Object> headers = new HashMap<>();

                    headers.put("Content-Type", SwitchOutRouteBuilder.TRANSFERS_CONTENT_TYPE_HEADER);
                    Object tracestate = exchange.getIn().getHeader("tracestate");
                    if (tracestate != null) {
                        headers.put("tracestate", tracestate);
                    }
                    headers.put("Host", transferService);
                    exchange.getIn().getHeaders().putAll(headers);
                })
                .process(pojoToString)
                .log(LoggingLevel.WARN, "calling PUT:/transfers/${header.tid}?host={{switch.host}}")
                .toD("rest:PUT:/transfers/${header.tid}?host={{switch.host}}");

    }
}
