package com.jx.tracker.risk.backfill;

import java.time.LocalDate;
import java.util.Optional;

public interface RiskBackfillPreflightRepository {

    Optional<String> latestSuccessfulFlywayVersion();

    boolean isOpenAshareTradingDay(LocalDate tradeDate);

    long enforcedGateCount();
}
