package com.jx.tracker.risk.runtime;

import java.time.Clock;
import java.time.LocalDate;

@FunctionalInterface
public interface RiskTradeDateResolver {

    LocalDate latestOpenDate(Clock clock);
}
