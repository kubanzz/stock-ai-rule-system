package com.jx.tracker.risk.backfill;

import java.time.LocalDate;

public interface RiskBackfillSourceProbe {

    SourceProbeResult probeAkTools(LocalDate endDate);

    SourceProbeResult probeDerivedGateway(LocalDate endDate);
}
