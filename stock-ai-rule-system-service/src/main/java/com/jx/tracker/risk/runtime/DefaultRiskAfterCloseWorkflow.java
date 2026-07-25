package com.jx.tracker.risk.runtime;

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
    public RiskWorkflowRunSummary runDaily(LocalDate tradeDate, List<String> symbols) {
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

    public RiskWorkflowRunSummary runManualMarket(LocalDate tradeDate) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        RiskWorkflowPlan plan = planner.planMarket();
        return workflow.run(plan.dailyRequest(
                tradeDate, LocalDateTime.now(clock), List.of(), modelVersion, afterCloseCutoff));
    }

    public RiskWorkflowRunSummary runManualStock(LocalDate tradeDate, String symbol) {
        if (tradeDate == null) {
            throw new IllegalArgumentException("tradeDate must not be null");
        }
        RiskWorkflowPlan plan = planner.plan(List.of(symbol));
        return workflow.run(plan.dailyRequest(
                tradeDate, LocalDateTime.now(clock), List.of(), modelVersion, afterCloseCutoff));
    }

    private LocalDateTime pointInTime(LocalDate tradeDate) {
        LocalDateTime configuredCutoff = tradeDate.atTime(afterCloseCutoff);
        LocalDateTime now = LocalDateTime.now(clock);
        return now.isBefore(configuredCutoff) ? now : configuredCutoff;
    }
}
