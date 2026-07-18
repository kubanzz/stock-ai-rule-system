package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskObjectKey;

import java.util.List;

public record RiskCollectionTask(
        String providerCode,
        String datasetCode,
        String scopeKey,
        List<RiskObjectKey> objects
) {

    public RiskCollectionTask {
        if (providerCode == null || providerCode.isBlank()
                || datasetCode == null || datasetCode.isBlank()
                || scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("providerCode, datasetCode and scopeKey are required");
        }
        objects = objects == null ? List.of() : List.copyOf(objects);
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("collection task objects must not be empty");
        }
    }
}
