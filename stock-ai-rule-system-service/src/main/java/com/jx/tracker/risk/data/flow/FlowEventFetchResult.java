package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.provider.RiskProviderBatch;

public record FlowEventFetchResult(RiskProviderBatch batch, FlowEventCoverageReport coverageReport) {
}
