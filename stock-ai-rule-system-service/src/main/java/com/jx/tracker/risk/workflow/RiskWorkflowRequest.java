package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.RiskHorizon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record RiskWorkflowRequest(
        List<RiskCollectionTask> collectionTasks,
        List<RiskHorizon> horizons,
        LocalDate collectionStartDate,
        LocalDate scoreStartDate,
        LocalDate endDate,
        LocalDateTime asOf,
        List<RiskSignalCandidate> signals,
        String modelVersion,
        LocalTime afterCloseCutoff
) {

    public static final LocalTime DEFAULT_AFTER_CLOSE_CUTOFF = LocalTime.of(20, 0);

    public RiskWorkflowRequest {
        collectionTasks = collectionTasks == null ? List.of() : List.copyOf(collectionTasks);
        horizons = horizons == null ? List.of() : List.copyOf(horizons);
        signals = signals == null ? List.of() : List.copyOf(signals);
        if (collectionTasks.isEmpty() || horizons.isEmpty()) {
            throw new IllegalArgumentException("collectionTasks and horizons must not be empty");
        }
        if (collectionStartDate == null || scoreStartDate == null || endDate == null || asOf == null) {
            throw new IllegalArgumentException("workflow dates and asOf are required");
        }
        if (scoreStartDate.isBefore(collectionStartDate) || endDate.isBefore(scoreStartDate)) {
            throw new IllegalArgumentException("dates must satisfy collectionStart <= scoreStart <= end");
        }
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
        afterCloseCutoff = afterCloseCutoff == null ? DEFAULT_AFTER_CLOSE_CUTOFF : afterCloseCutoff;
    }

    public RiskWorkflowRequest(
            List<RiskCollectionTask> collectionTasks,
            List<RiskHorizon> horizons,
            LocalDate collectionStartDate,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf,
            List<RiskSignalCandidate> signals,
            String modelVersion
    ) {
        this(collectionTasks, horizons, collectionStartDate, scoreStartDate, endDate,
                asOf, signals, modelVersion, DEFAULT_AFTER_CLOSE_CUTOFF);
    }

    public static RiskWorkflowRequest daily(
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<RiskCollectionTask> tasks,
            List<RiskHorizon> horizons,
            List<RiskSignalCandidate> signals,
            String modelVersion
    ) {
        return new RiskWorkflowRequest(
                tasks, horizons, tradeDate.minusYears(5), tradeDate, tradeDate,
                asOf, signals, modelVersion, DEFAULT_AFTER_CLOSE_CUTOFF);
    }

    public static RiskWorkflowRequest fiveYearBackfill(
            LocalDate endDate,
            LocalDateTime asOf,
            List<RiskCollectionTask> tasks,
            List<RiskHorizon> horizons,
            List<RiskSignalCandidate> signals,
            String modelVersion
    ) {
        LocalDate startDate = endDate.minusYears(5);
        return new RiskWorkflowRequest(
                tasks, horizons, startDate, startDate, endDate,
                asOf, signals, modelVersion, DEFAULT_AFTER_CLOSE_CUTOFF);
    }
}
