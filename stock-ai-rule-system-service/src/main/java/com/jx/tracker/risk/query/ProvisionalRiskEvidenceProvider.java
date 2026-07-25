package com.jx.tracker.risk.query;

import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;

import java.util.List;
import java.util.Map;

@FunctionalInterface
public interface ProvisionalRiskEvidenceProvider {

    Map<Long, List<RiskEvidence>> load(List<RiskScoreSnapshotEntity> snapshots);

    static ProvisionalRiskEvidenceProvider empty() {
        return ignored -> Map.of();
    }
}
