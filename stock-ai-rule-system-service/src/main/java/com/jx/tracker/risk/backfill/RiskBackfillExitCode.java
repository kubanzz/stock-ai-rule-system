package com.jx.tracker.risk.backfill;

public enum RiskBackfillExitCode {
    SUCCESS(0),
    CONFIGURATION_ERROR(2),
    PREFLIGHT_ERROR(3),
    SAMPLE_EXECUTION_ERROR(4),
    SAMPLE_GATE_REJECTED(5),
    FULL_EXECUTION_ERROR(6),
    FINAL_VALIDATION_ERROR(7),
    REPORT_WRITE_ERROR(8);

    private final int code;

    RiskBackfillExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
