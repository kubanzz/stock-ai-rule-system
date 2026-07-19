package com.jx.tracker.risk.model;

public record RiskObjectKey(RiskObjectType objectType, String objectId) {

    public RiskObjectKey {
        RiskContractValidation.required(objectType, "objectType");
        RiskContractValidation.notBlank(objectId, "objectId");
    }
}
