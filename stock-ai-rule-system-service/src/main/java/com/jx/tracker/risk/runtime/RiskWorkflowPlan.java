package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.workflow.RiskCollectionTask;
import com.jx.tracker.risk.workflow.RiskWorkflowRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public record RiskWorkflowPlan(
        List<RiskObjectKey> stockObjects,
        List<RiskCollectionTask> collectionTasks,
        List<RiskHorizon> horizons
) {

    public RiskWorkflowPlan {
        stockObjects = stockObjects == null ? List.of() : List.copyOf(stockObjects);
        collectionTasks = collectionTasks == null ? List.of() : List.copyOf(collectionTasks);
        horizons = horizons == null ? List.of() : List.copyOf(horizons);
        if (stockObjects.isEmpty() || collectionTasks.isEmpty() || horizons.isEmpty()) {
            throw new IllegalArgumentException("risk workflow plan must not be empty");
        }
    }

    public RiskWorkflowRequest dailyRequest(
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<RiskSignalCandidate> signals,
            String modelVersion,
            LocalTime afterCloseCutoff
    ) {
        return new RiskWorkflowRequest(
                collectionTasks,
                horizons,
                tradeDate.minusYears(5),
                tradeDate,
                tradeDate,
                asOf,
                signals,
                modelVersion,
                afterCloseCutoff
        );
    }

    public RiskWorkflowRequest fiveYearBackfillRequest(
            LocalDate endDate,
            LocalDateTime asOf,
            List<RiskSignalCandidate> signals,
            String modelVersion,
            LocalTime afterCloseCutoff
    ) {
        LocalDate startDate = endDate.minusYears(5);
        return new RiskWorkflowRequest(
                collectionTasks,
                horizons,
                startDate,
                startDate,
                endDate,
                asOf,
                signals,
                modelVersion,
                afterCloseCutoff
        );
    }
}
