package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskSnapshot;

import java.time.LocalDateTime;

public record StoredRiskSnapshot(
        long id,
        RiskSnapshot snapshot,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        RiskDataQualityStatus qualityStatus
) {

    public StoredRiskSnapshot {
        if (id <= 0 || snapshot == null || observedAt == null || availableAt == null || qualityStatus == null) {
            throw new IllegalArgumentException("stored snapshot id, value, timestamps and quality are required");
        }
        if (availableAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("availableAt must not be before observedAt");
        }
    }
}
