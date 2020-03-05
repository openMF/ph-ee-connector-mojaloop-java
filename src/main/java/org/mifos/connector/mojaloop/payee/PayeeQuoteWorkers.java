package org.mifos.connector.mojaloop.payee;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.mojaloop.camel.config.CamelProperties;
import org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_DESTINATION;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class PayeeQuoteWorkers {

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ZeebeClient zeebeClient;

    @Autowired
    private ProducerTemplate producerTemplate;

    @Autowired
    private CamelContext camelContext;

    @Value("#{'${dfspids}'.split(',')}")
    private List<String> dfspids;

    @PostConstruct
    public void setupWorkers() {
        for (String dfspId : dfspids) {
            logger.info("## generating payee quote Zeebe workers for DFSPID: {}", dfspId);

            zeebeClient.newWorker()
                    .jobType("payee-quote-" + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());

                        Map<String, Object> variables = job.getVariablesAsMap();
                        variables.put("quoteResult", "SUCCESS"); // TODO

                        client.newCompleteCommand(job.getKey())
                                .variables(variables)
                                .send();
                    })
                    .open();

            zeebeClient.newWorker()
                    .jobType("payee-quote-response-" + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> variables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        ZeebeProcessStarter.zeebeVariablesToCamelHeaders(variables, exchange,
                                "qid",
                                FSPIOP_SOURCE.headerName(),
                                FSPIOP_DESTINATION.headerName(),
                                "Date",
                                "traceparent"
                        );
                        exchange.getIn().setBody(variables.get(CamelProperties.TRANSACTION_REQUEST));

                        producerTemplate.send("direct:send-quote-to-switch", exchange);
                        client.newCompleteCommand(job.getKey()).send();
                    })
                    .open();
        }
    }
}
