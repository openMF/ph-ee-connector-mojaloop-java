package org.mifos.connector.mojaloop.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties
public class PartyProperties {

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

    public Party getPartyByDomainAndFspId(String domain, String fspId) {
        List<Party> filteredParties = getParties().stream()
                .filter(t -> t.getDomain().equals(domain))
                .toList();
        if (fspId == null || fspId.isEmpty() || filteredParties.size() == 1) {
            return filteredParties.stream()
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("Party with domain: " + domain + ", not configured!"));
        }

        return filteredParties.stream()
                .filter(t -> t.getFspId().equals(fspId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Party with domain: " + domain + " and payeeFsp: " + fspId + ", not configured!"));
    }
}
