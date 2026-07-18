package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;

@FunctionalInterface
public interface RiskSnapshotEvaluator {

    RiskScoreResult evaluate(RiskScoreRequest request);
}
