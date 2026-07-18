package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
import com.jx.tracker.risk.engine.RiskLayerScoreRequest;
import com.jx.tracker.risk.engine.RiskScoringEngine;

@FunctionalInterface
public interface RiskSnapshotEvaluator {

    RiskScoreResult evaluate(RiskScoreRequest request);

    default RiskScoreResult evaluateLayers(RiskLayerScoreRequest request) {
        return new RiskScoringEngine().scoreLayers(request);
    }
}
