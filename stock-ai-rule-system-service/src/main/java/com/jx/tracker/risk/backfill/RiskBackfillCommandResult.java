package com.jx.tracker.risk.backfill;

import java.nio.file.Path;

public record RiskBackfillCommandResult(
        RiskBackfillExitCode exitCode,
        Path reportPath,
        RiskBackfillReport report
) {

    public RiskBackfillCommandResult {
        if (exitCode == null || report == null) {
            throw new IllegalArgumentException("command result exitCode and report are required");
        }
    }
}
