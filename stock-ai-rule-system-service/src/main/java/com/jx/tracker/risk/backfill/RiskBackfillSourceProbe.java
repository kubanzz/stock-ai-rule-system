package com.jx.tracker.risk.backfill;

import java.time.LocalDate;

public interface RiskBackfillSourceProbe {

    default SourceProbeResult probeTushare(LocalDate endDate) {
        return SourceProbeResult.unreachable(
                "tushare", "TuShare probe is not configured");
    }

    SourceProbeResult probeAkTools(LocalDate endDate);

    SourceProbeResult probeDerivedGateway(LocalDate endDate);
}
