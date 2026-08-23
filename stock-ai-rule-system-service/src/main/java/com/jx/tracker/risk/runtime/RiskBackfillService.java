package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.workflow.RiskWarningWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/** 仅供内部显式调用；默认关闭且不注册自动 runner。 */
public final class RiskBackfillService {

    private final RiskWorkflowPlanner planner;
    private final RiskSignalCandidateReader candidateReader;
    private final RiskWarningWorkflow workflow;
    private final Clock clock;
    private final boolean backfillEnabled;
    private final String modelVersion;
    private final LocalTime afterCloseCutoff;

    public RiskBackfillService(
            RiskWorkflowPlanner planner,
            RiskSignalCandidateReader candidateReader,
            RiskWarningWorkflow workflow,
            Clock clock,
            RiskWarningProperties properties
    ) {
        if (planner == null || candidateReader == null || workflow == null
                || clock == null || properties == null) {
            throw new IllegalArgumentException("risk backfill dependencies must not be null");
        }
        this.planner = planner;
        this.candidateReader = candidateReader;
        this.workflow = workflow;
        this.clock = clock;
        this.backfillEnabled = properties.isBackfillEnabled();
        this.modelVersion = properties.requiredModelVersion();
        this.afterCloseCutoff = properties.requiredAfterCloseCutoff();
    }

    public Optional<RiskWorkflowRunSummary> runFiveYearBackfill(
            LocalDate endDate,
            List<String> symbols
    ) {
        return runFiveYearBackfill(endDate, symbols, false);
    }

    /** 仅重用已落库的观测、事件与行业暴露进行评分，用于采集成功后中断的恢复。 */
    public Optional<RiskWorkflowRunSummary> runFiveYearBackfillFromStoredData(
            LocalDate endDate,
            List<String> symbols
    ) {
        return runFiveYearBackfill(endDate, symbols, true);
    }

    private Optional<RiskWorkflowRunSummary> runFiveYearBackfill(
            LocalDate endDate,
            List<String> symbols,
            boolean storedDataOnly
    ) {
        if (!backfillEnabled) {
            return Optional.empty();
        }
        if (endDate == null) {
            throw new IllegalArgumentException("endDate must not be null");
        }
        RiskWorkflowPlan plan = planner.plan(symbols);
        LocalDateTime asOf = pointInTime(endDate);
        List<RiskSignalCandidate> signals = candidateReader.read(
                endDate, plan.stockObjects(), plan.horizons(), asOf);
        var request = plan.fiveYearBackfillRequest(
                endDate, asOf, signals, modelVersion, afterCloseCutoff);
        return Optional.of(storedDataOnly
                ? workflow.scoreStoredData(request)
                : workflow.run(request));
    }

    private LocalDateTime pointInTime(LocalDate date) {
        LocalDateTime configuredCutoff = date.atTime(afterCloseCutoff);
        LocalDateTime now = LocalDateTime.now(clock);
        return now.isBefore(configuredCutoff) ? now : configuredCutoff;
    }
}
