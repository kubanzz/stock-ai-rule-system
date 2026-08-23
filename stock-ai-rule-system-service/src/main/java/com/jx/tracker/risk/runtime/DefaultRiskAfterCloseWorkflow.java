package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.engine.RiskCoverageProfileCatalog;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.workflow.RiskAfterCloseWorkflow;
import com.jx.tracker.risk.workflow.RiskWarningWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public final class DefaultRiskAfterCloseWorkflow implements RiskAfterCloseWorkflow {

    private final RiskWorkflowPlanner planner;
    private final RiskSignalCandidateReader candidateReader;
    private final RiskWarningWorkflow workflow;
    private final Clock clock;
    private final String modelVersion;
    private final LocalTime afterCloseCutoff;

    public DefaultRiskAfterCloseWorkflow(
            RiskWorkflowPlanner planner,
            RiskSignalCandidateReader candidateReader,
            RiskWarningWorkflow workflow,
            Clock clock,
            RiskWarningProperties properties
    ) {
        if (planner == null || candidateReader == null || workflow == null
                || clock == null || properties == null) {
            throw new IllegalArgumentException("risk runtime dependencies must not be null");
        }
        this.planner = planner;
        this.candidateReader = candidateReader;
        this.workflow = workflow;
        this.clock = clock;
        this.modelVersion = properties.requiredModelVersion();
        this.afterCloseCutoff = properties.requiredAfterCloseCutoff();
    }

    @Override
    public synchronized RiskWorkflowRunSummary runDaily(
            LocalDate tradeDate,
            List<String> symbols
    ) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        RiskWorkflowPlan plan = planner.plan(symbols);
        LocalDateTime asOf = pointInTime(tradeDate);
        List<RiskSignalCandidate> signals = candidateReader.read(
                tradeDate, plan.stockObjects(), plan.horizons(), asOf);
        return workflow.run(plan.dailyRequest(
                tradeDate, asOf, signals, modelVersion, afterCloseCutoff));
    }

    public String normalizeStockSymbol(String symbol) {
        return planner.normalizeStockSymbol(symbol);
    }

    public synchronized RiskWorkflowRunSummary runManualMarket(LocalDate tradeDate) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        LocalDateTime asOf = LocalDateTime.now(clock);
        RiskWorkflowRunSummary immediate = workflow.run(
                planner.planMarketImmediate().manualMarketSyncRequest(
                        tradeDate, asOf, modelVersion, afterCloseCutoff));
        RiskWorkflowRunSummary deferred = workflow.run(
                planner.planMarketDeferred().manualMarketSyncRequest(
                        tradeDate, asOf, modelVersion, afterCloseCutoff));
        return add(immediate, deferred);
    }

    public synchronized RiskWorkflowRunSummary runManualStock(
            LocalDate tradeDate,
            String symbol
    ) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        RiskWorkflowPlan plan = planner.planStockSync(symbol);
        return workflow.run(plan.manualStockSyncRequest(
                tradeDate, LocalDateTime.now(clock), modelVersion, afterCloseCutoff));
    }

    public synchronized RiskWorkflowRunSummary rebuildRecentSectorScores(LocalDate endDate) {
        if (endDate == null) {
            throw new IllegalArgumentException("endDate must not be null");
        }
        RiskWorkflowPlan plan = planner.planMarketImmediate();
        return workflow.scoreStoredData(plan.recentSectorRebuildRequest(
                endDate,
                LocalDateTime.now(clock),
                RiskCoverageProfileCatalog.OBJECT_AWARE_MODEL_VERSION,
                afterCloseCutoff));
    }

    private LocalDateTime pointInTime(LocalDate tradeDate) {
        LocalDateTime configuredCutoff = tradeDate.atTime(afterCloseCutoff);
        LocalDateTime now = LocalDateTime.now(clock);
        return now.isBefore(configuredCutoff) ? now : configuredCutoff;
    }

    private RiskWorkflowRunSummary add(
            RiskWorkflowRunSummary left,
            RiskWorkflowRunSummary right
    ) {
        return new RiskWorkflowRunSummary(
                Math.addExact(left.observationCount(), right.observationCount()),
                Math.addExact(left.eventCount(), right.eventCount()),
                Math.addExact(left.snapshotCount(), right.snapshotCount()),
                Math.addExact(left.evidenceCount(), right.evidenceCount()),
                Math.addExact(left.gateCount(), right.gateCount()),
                Math.addExact(left.checkpointCount(), right.checkpointCount()),
                Math.addExact(left.unavailableDatasetCount(), right.unavailableDatasetCount())
        );
    }
}
