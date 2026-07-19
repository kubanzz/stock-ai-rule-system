package com.jx.tracker.risk.dashboard;

import com.jx.tracker.risk.model.RiskHorizon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface StockDashboardRiskReader {

    Map<String, StockDashboardRiskOverlay> findBySymbols(
            LocalDate tradeDate,
            RiskHorizon horizon,
            List<String> symbols,
            LocalDateTime asOf
    );
}
