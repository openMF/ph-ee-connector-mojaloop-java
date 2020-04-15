package org.mifos.connector.mojaloop.payee;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.phee.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchRequestDTO;
import org.mifos.phee.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.mifos.phee.common.mojaloop.ilp.Ilp;
import org.mifos.phee.common.mojaloop.type.TransferState;
import org.mifos.phee.common.util.ContextUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mojaloop.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.SWITCH_TRANSFER_REQUEST;
import static org.mifos.connector.mojaloop.camel.config.CamelProperties.TRANSACTION_ID;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class PayeeTransferRoutes extends ErrorHandlerRouteBuilder {

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    public PayeeTransferRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/transfers")
                .log(LoggingLevel.WARN, "######## SWITCH -> PAYEE - forward transfer request - STEP 2")
                .setProperty(SWITCH_TRANSFER_REQUEST, bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchRequestDTO.class)
                .process(exchange -> {
                    TransferSwitchRequestDTO request = exchange.getIn().getBody(TransferSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.parse(request.getIlpPacket(), request.getCondition());

                    Map<String, Object> variables = new HashMap<>();
                    variables.put(SWITCH_TRANSFER_REQUEST, exchange.getProperty(SWITCH_TRANSFER_REQUEST));
                    variables.put(TRANSACTION_ID, ilp.getTransaction().getTransactionId());
                    variables.put(FSPIOP_SOURCE.headerName(), request.getPayeeFsp());
                    variables.put(FSPIOP_DESTINATION.headerName(), request.getPayerFsp());
                    variables.put("Date", exchange.getIn().getHeader("Date"));
                    variables.put("traceparent", exchange.getIn().getHeader("traceparent"));

                    zeebeClient.newPublishMessageCommand()
                            .messageName("TransferMessage-DFSPID")  // TODO externalize
                            .correlationKey(ilp.getTransaction().getTransactionId())
                            .variables(variables)
                            .send();
                });

        from("direct:send-transfer-error-to-switch")
                .id("send-transfer-error-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchRequestDTO.class)
                .process(e -> {
                    e.getIn().setBody(e.getProperty(ERROR_INFORMATION));
                    TransferSwitchRequestDTO request = e.getIn().getBody(TransferSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.parse(request.getIlpPacket(), request.getCondition());
                    mojaloopUtil.setTransferHeaders(e, ilp.getTransaction());
                })
                .toD("rest:PUT:/transfers/${header."+TRANSACTION_ID+"}/error?host={{switch.host}}");

        from("direct:send-transfer-to-switch")
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
                    mojaloopUtil.setTransferHeaders(exchange, ilp.getTransaction());
                })
                .process(pojoToString)
                .toD("rest:PUT:/transfers/${header."+TRANSACTION_ID+"}?host={{switch.host}}");
    }
}