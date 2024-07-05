package org.mifos.connector.mojaloop.properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConfigurationProperties
public class PartyProperties {

    private static final Logger log = LoggerFactory.getLogger(PartyProperties.class);
    private List<Party> parties = new ArrayList<>();

    public PartyProperties() {
    }

    public List<Party> getParties() {
        return parties;
    }

    public void setParties(List<Party> parties) {
        this.parties = parties;
    }

    public Party getPartyByDfsp(String dfsp) {
        return getParties().stream()
                .filter(t -> t.getFspId().equals(dfsp))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Party with dfspId: " + dfsp + ", not configured!"));
    }

    public Party getPartyByTenant(String tenant) {
        return getParties().stream()
                .filter(t -> t.getTenantId().equals(tenant))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Party with tenant: " + tenant + ", not configured!"));
    }

    public Party getPartyByDomain(String domain, String payeeFsp) {
        List<Party> filteredParties = getParties().stream()
                .filter(t -> t.getDomain().equals(domain))
                .toList();
        log.info("Filtered party: " + filteredParties.toString());
        if (payeeFsp == null || payeeFsp.isEmpty() || filteredParties.size() == 1) {
            return filteredParties.stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Party with domain: " + domain + ", not configured!"));
        }

        return filteredParties.stream()
                .filter(t -> t.getFspId().equals(payeeFsp))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Party with domain: " + domain + " and payeeFsp: " + payeeFsp + ", not configured!"));
    }
}
