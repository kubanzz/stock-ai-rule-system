package com.jx.tracker.risk.query;

import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ProvisionalRiskEvidenceProvider {

    Map<Long, List<RiskEvidence>> load(
            List<RiskScoreSnapshotEntity> snapshots,
            boolean allowPublishedExposureFallback
    );

    static ProvisionalRiskEvidenceProvider empty() {
        return (ignored, allowPublishedExposureFallback) -> Map.of();
    }
}
