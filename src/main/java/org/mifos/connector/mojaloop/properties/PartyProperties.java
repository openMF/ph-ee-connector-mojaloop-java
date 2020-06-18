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

    public Party getParty(String dfspId) {
        if (dfspId == null) {
            throw new RuntimeException("Party with dfspid: " + dfspId + ", not configured!");
        }

        return getParties().stream()
                .filter(t -> t.getFspId().equals(dfspId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Party with dfspId: " + dfspId + ", not configured!"));
    }
}
