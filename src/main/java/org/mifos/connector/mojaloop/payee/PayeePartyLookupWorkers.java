package org.mifos.connector.mojaloop.payee;

import io.zeebe.client.ZeebeClient;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

import static org.mifos.connector.mojaloop.zeebe.ZeebeProcessStarter.zeebeVariablesToCamelHeaders;
import static org.mifos.phee.common.mojaloop.type.MojaloopHeaders.FSPIOP_SOURCE;

@Component
public class PayeePartyLookupWorkers {

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
            logger.info("## generating payee party Zeebe workers for DFSPID: {}", dfspId);

            zeebeClient.newWorker()
                    .jobType("payee-party-lookup-error-" + dfspId)
                    .handler((client, job) -> { // TODO implement error handler
                        logger.error("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        client.newCompleteCommand(job.getKey()).send();
                    })
                    .name("payee-party-lookup-error-" + dfspId)
                    .maxJobsActive(10)
                    .open();

            zeebeClient.newWorker()
                    .jobType("payee-party-lookup-response-" + dfspId)
                    .handler((client, job) -> {
                        logger.info("Job '{}' started from process '{}' with key {}", job.getType(), job.getBpmnProcessId(), job.getKey());
                        Map<String, Object> variables = job.getVariablesAsMap();

                        Exchange exchange = new DefaultExchange(camelContext);
                        zeebeVariablesToCamelHeaders(variables, exchange,
                                "partyIdType",
                                "partyId",
                                FSPIOP_SOURCE.headerName(),
                                "traceparent",
                                "Date"
                        );

                        producerTemplate.send("direct:send-parties-callback", exchange);
                        client.newCompleteCommand(job.getKey()).send();
                    })
                    .name("payee-party-lookup-response-" + dfspId)
                    .maxJobsActive(10)
                    .open();
        }
    }
}
