package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.time.LocalDate;
import java.util.List;

public record FlowEventSourceRequest(FlowEventDataset dataset, RiskProviderRequest providerRequest) {

    public FlowEventSourceRequest {
        if (dataset == null || providerRequest == null) {
            throw new IllegalArgumentException("dataset and providerRequest are required");
        }
    }

    public List<RiskObjectKey> objects() {
        return providerRequest.objects();
    }

    public List<RiskHorizon> horizons() {
        return providerRequest.horizons();
    }

    public LocalDate startDate() {
        return providerRequest.startDate();
    }

    public LocalDate endDate() {
        return providerRequest.endDate();
    }

    public RiskIngestionCheckpoint checkpoint() {
        return providerRequest.checkpoint();
    }
}
