package com.jx.tracker.risk.backfill;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface RiskBackfillReadinessRepository {

    RiskBackfillReadinessData load(
            String modelVersion,
            LocalDate scoreStartDate,
            LocalDate endDate,
            LocalDateTime asOf,
            List<String> stockSymbols
    );
}
