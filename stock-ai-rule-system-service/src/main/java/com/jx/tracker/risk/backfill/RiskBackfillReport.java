package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record RiskBackfillReport(
        int schemaVersion,
        String runId,
        RiskBackfillMode mode,
        String modelVersion,
        String stage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int exitCode,
        String status,
        LocalDate endDate,
        LocalDate scoreStartDate,
        LocalDate collectionStartDate,
        List<String> sampleSymbols,
        int activeStockCount,
        int fullChunkCount,
        RiskBackfillPreflight preflight,
        RiskWorkflowRunSummary sampleSummary,
        RiskWorkflowRunSummary fullSummary,
        RiskBackfillReadiness sampleReadiness,
        RiskBackfillReadiness finalReadiness,
        boolean sampleGatePassed,
        List<String> failures,
        ErrorSummary error
) {

    public RiskBackfillReport {
        sampleSymbols = sampleSymbols == null ? List.of() : List.copyOf(sampleSymbols);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public boolean isPassedSampleGateFor(String expectedModelVersion, LocalDate expectedEndDate) {
        return sampleGatePassed
                && (mode == RiskBackfillMode.SAMPLE || mode == RiskBackfillMode.STAGED)
                && expectedModelVersion != null
                && expectedModelVersion.equals(modelVersion)
                && expectedEndDate != null
                && expectedEndDate.equals(endDate)
                && finishedAt != null;
    }

    public record ErrorSummary(String type, String message) {
    }
}
