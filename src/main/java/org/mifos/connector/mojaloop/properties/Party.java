package org.mifos.connector.mojaloop.properties;

public class Party {

    private String partyIdType, partyId, tenantId, fspId;

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

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getFspId() {
        return fspId;
    }

    public void setFspId(String fspId) {
        this.fspId = fspId;
    }
}
