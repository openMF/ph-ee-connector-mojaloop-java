package org.mifos.connector.mojaloop.properties;

public class Party {

    private String partyIdType, partyId, dfspId;

    public Party() {
    }

    public String getPartyIdType() {
        return partyIdType;
    }

    public void setPartyIdType(String partyIdType) {
        this.partyIdType = partyIdType;
    }

    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }

    public String getDfspId() {
        return dfspId;
    }

    public void setDfspId(String dfspId) {
        this.dfspId = dfspId;
    }
}
