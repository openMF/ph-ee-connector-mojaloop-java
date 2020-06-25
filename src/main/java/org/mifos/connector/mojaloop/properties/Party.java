package org.mifos.connector.mojaloop.properties;

public class Party {

    private String tenantId, fspId, domain;

    public Party() {
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

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }
}
