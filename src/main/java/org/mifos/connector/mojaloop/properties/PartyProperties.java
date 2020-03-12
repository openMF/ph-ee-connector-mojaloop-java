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

    public Party getParty(String partyIdType, String partyId) {
        if (partyIdType == null || partyId == null) {
            return null;
        }

        return getParties().stream()
                .filter(t -> t.getPartyIdType().equals(partyIdType) && t.getPartyId().equals(partyId))
                .findFirst()
                .orElse(null);
    }
}
