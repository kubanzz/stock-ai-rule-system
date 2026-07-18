package com.jx.tracker.risk.workflow;

public record RiskWorkflowRunSummary(
        int observationCount,
        int eventCount,
        int snapshotCount,
        int evidenceCount,
        int gateCount,
        int checkpointCount,
        int unavailableDatasetCount
) {
}
