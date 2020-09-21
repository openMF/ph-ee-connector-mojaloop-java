package org.mifos.connector.mojaloop.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ilp.conditions.models.pdp.Transaction;
import io.zeebe.client.ZeebeClient;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.mifos.connector.common.camel.ErrorHandlerRouteBuilder;
import org.mifos.connector.common.channel.dto.TransactionChannelRequestDTO;
import org.mifos.connector.common.mojaloop.dto.MoneyData;
import org.mifos.connector.common.mojaloop.dto.QuoteSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.dto.TransferSwitchRequestDTO;
import org.mifos.connector.common.mojaloop.dto.TransferSwitchResponseDTO;
import org.mifos.connector.common.mojaloop.ilp.Ilp;
import org.mifos.connector.common.mojaloop.type.TransferState;
import org.mifos.connector.common.util.ContextUtil;
import org.mifos.connector.mojaloop.camel.trace.AddTraceHeaderProcessor;
import org.mifos.connector.mojaloop.camel.trace.GetCachedTransactionIdProcessor;
import org.mifos.connector.mojaloop.ilp.IlpBuilder;
import org.mifos.connector.mojaloop.util.MojaloopUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.connector.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeMessages.TRANSFER_MESSAGE;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.CHANNEL_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.ERROR_INFORMATION;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.SWITCH_TRANSFER_REQUEST;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mojaloop.zeebe.ZeebeVariables.TRANSFER_FAILED;

@Component
public class TransferRoutes extends ErrorHandlerRouteBuilder {

    @Autowired
    private IlpBuilder ilpBuilder;

    @Autowired
    private Processor pojoToString;

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private MojaloopUtil mojaloopUtil;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AddTraceHeaderProcessor addTraceHeaderProcessor;

    @Autowired
    private GetCachedTransactionIdProcessor getCachedTransactionIdProcessor;

    @Autowired
    private TransferResponseProcessor transferResponseProcessor;

    public TransferRoutes() {
        super.configure();
    }

    @Override
    public void configure() {
        from("rest:POST:/switch/transfers")
                .setProperty(SWITCH_TRANSFER_REQUEST, bodyAs(String.class))
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchRequestDTO.class)
                .process(exchange -> {
                    TransferSwitchRequestDTO request = exchange.getIn().getBody(TransferSwitchRequestDTO.class);
                    Ilp ilp = ilpBuilder.parse(request.getIlpPacket(), request.getCondition());

                    Map<String, Object> variables = new HashMap<>();
                    variables.put(SWITCH_TRANSFER_REQUEST, exchange.getProperty(SWITCH_TRANSFER_REQUEST));
                    String transactionId = ilp.getTransaction().getTransactionId();
                    exchange.setProperty(TRANSACTION_ID, transactionId);
                    variables.put(TRANSACTION_ID, transactionId);
                    variables.put(FSPIOP_SOURCE.headerName(), request.getPayeeFsp());
                    variables.put(FSPIOP_DESTINATION.headerName(), request.getPayerFsp());
                    variables.put("Date", exchange.getIn().getHeader("Date"));
                    variables.put("traceparent", exchange.getIn().getHeader("traceparent"));

                    zeebeClient.newPublishMessageCommand()
                            .messageName(TRANSFER_MESSAGE)
                            .correlationKey(transactionId)
                            .variables(variables)
                            .send()
                            ;
                })
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYEE - forward transfer request ${exchangeProperty."+TRANSACTION_ID+"} - STEP 2")
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(202));

        from("rest:PUT:/switch/transfers/{"+TRANSACTION_ID+"}")
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchResponseDTO.class)
                .process(getCachedTransactionIdProcessor)
                .to("direct:transfers-step4");

        from("direct:transfers-step4")
                .log(LoggingLevel.INFO, "######## SWITCH -> PAYER - response for transfer request ${header."+TRANSACTION_ID+"} - STEP 4")
                .process(transferResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("rest:PUT:/switch/transfers/{"+TRANSACTION_ID+"}/error")
                .log(LoggingLevel.ERROR, "######## SWITCH -> PAYER - transfer error ${header."+TRANSACTION_ID+"}")
                .process(getCachedTransactionIdProcessor)
                .setProperty(TRANSFER_FAILED, constant(true))
                .process(transferResponseProcessor)
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_RESPONSE_CODE, constant(200));

        from("direct:send-transfer-error-to-switch")
                .id("send-transfer-error-to-switch")
                .unmarshal().json(JsonLibrary.Jackson, TransferSwitchRequestDTO.class)
                .process(e -> {
                    TransferSwitchRequestDTO request = e.getIn().getBody(TransferSwitchRequestDTO.class);
                    mojaloopUtil.setTransferHeadersResponse(e, ilpBuilder.parse(request.getIlpPacket(), request.getCondition()).getTransaction());
                    e.getIn().setBody(e.getProperty(ERROR_INFORMATION));
                })
                .toD("rest:PUT:/transfers/${exchangeProperty."+TRANSACTION_ID+"}/error?host={{switch.transfers-host}}");

        from("direct:send-transfer-to-switch")
                .log(LoggingLevel.INFO, "######## PAYEE -> SWITCH - transfer response ${exchangeProperty."+TRANSACTION_ID+"} - STEP 3")
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
                    mojaloopUtil.setTransferHeadersResponse(exchange, ilp.getTransaction());
                })
                .process(pojoToString)
                .toD("rest:PUT:/transfers/${exchangeProperty."+TRANSACTION_ID+"}?host={{switch.transfers-host}}");

        from("direct:send-transfer")
                .id("send-transfer")
                .log(LoggingLevel.INFO, "######## PAYER -> SWITCH - transfer request ${exchangeProperty."+TRANSACTION_ID+"} - STEP 1")
                .unmarshal().json(JsonLibrary.Jackson, QuoteSwitchResponseDTO.class)
                .process(exchange -> {
                    QuoteSwitchResponseDTO quoteResponse = exchange.getIn().getBody(QuoteSwitchResponseDTO.class);
                    Ilp ilp = ilpBuilder.parse(quoteResponse.getIlpPacket(), quoteResponse.getCondition());

                    Transaction transaction = ilp.getTransaction();
                    TransferSwitchRequestDTO request = new TransferSwitchRequestDTO(
                            transaction.getTransactionId(),
                            transaction.getPayer().getPartyIdInfo().getFspId(),
                            transaction.getPayee().getPartyIdInfo().getFspId(),
                            new MoneyData(transaction.getAmount().getAmount(), transaction.getAmount().getCurrency()),
                            ilp.getPacket(),
                            ilp.getCondition(),
                            ContextUtil.parseDate(quoteResponse.getExpiration()).plusHours(1),
                            objectMapper.readValue(exchange.getProperty(CHANNEL_REQUEST, String.class), TransactionChannelRequestDTO.class).getExtensionList());

                    exchange.getIn().setBody(request);
                    mojaloopUtil.setTransferHeadersRequest(exchange, transaction);
                })
                .process(pojoToString)
                .process(addTraceHeaderProcessor)
                .toD("rest:POST:/transfers?host={{switch.transfers-host}}");
    }
}