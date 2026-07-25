package com.jx.tracker.risk.sync;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

public record RiskSyncJob(
        String jobId,
        String scopeKey,
        String status,
        String phase,
        int progress,
        LocalDate tradeDate,
        int observationCount,
        int eventCount,
        int snapshotCount,
        int evidenceCount,
        int unavailableDatasetCount,
        String message,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
) {
    private static final Set<String> ACTIVE_STATUSES = Set.of("queued", "running");

    public RiskSyncJob {
        if (jobId == null || jobId.isBlank() || scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("risk sync job identity is required");
        }
        if (status == null || status.isBlank() || phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("risk sync job status and phase are required");
        }
        if (progress < 0 || progress > 100 || createdAt == null) {
            throw new IllegalArgumentException("invalid risk sync job progress or creation time");
        }
    }

    public boolean active() {
        return ACTIVE_STATUSES.contains(status);
    }
}
