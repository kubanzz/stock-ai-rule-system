package com.jx.tracker.risk.sync;

import java.util.List;

public record RiskSyncStatus(
        RiskSyncJob latestMarketJob,
        List<RiskSyncJob> activeJobs
) {
    public RiskSyncStatus {
        activeJobs = activeJobs == null ? List.of() : List.copyOf(activeJobs);
    }
}
