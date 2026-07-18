package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
import com.jx.tracker.risk.engine.RiskScoringEngine;

public final class DefaultRiskSnapshotEvaluator implements RiskSnapshotEvaluator {

    private final RiskScoringEngine engine;

    public DefaultRiskSnapshotEvaluator(RiskScoringEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must not be null");
        }
        this.engine = engine;
    }

    @Override
    public RiskScoreResult evaluate(RiskScoreRequest request) {
        return engine.score(request);
    }
}
