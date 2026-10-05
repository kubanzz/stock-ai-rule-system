package com.jx.tracker.risk.dashboard;

import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.risk.model.RiskHorizon;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public interface StockDashboardRiskReader {

    Map<String, StockDashboardRiskOverlay> findBySymbols(
            LocalDate tradeDate,
            RiskHorizon horizon,
            List<String> symbols,
            LocalDateTime asOf
    );

    /** 股票快照可以共享，方向相关的闸门结论必须属于具体信号。 */
    default Map<Long, StockDashboardRiskOverlay> findBySignals(
            LocalDate tradeDate, RiskHorizon horizon, List<StockSignalDaily> signals, LocalDateTime asOf
    ) {
        if (signals == null || signals.isEmpty()) {
            return Map.of();
        }
        Map<String, StockDashboardRiskOverlay> bySymbol = findBySymbols(tradeDate, horizon,
                signals.stream().map(StockSignalDaily::getSymbol).distinct().toList(), asOf);
        Map<Long, StockDashboardRiskOverlay> result = new LinkedHashMap<>();
        for (StockSignalDaily signal : signals) {
            StockDashboardRiskOverlay overlay = bySymbol.get(signal.getSymbol());
            if (signal.getId() == null || overlay == null) {
                continue;
            }
            boolean legacy = (signal.getStrategyCode() == null
                    || StockSignalDaily.LEGACY_STRATEGY_CODE.equals(signal.getStrategyCode()))
                    && (signal.getStrategyVersion() == null
                    || StockSignalDaily.LEGACY_STRATEGY_VERSION.equals(signal.getStrategyVersion()));
            result.put(signal.getId(), legacy ? overlay : new StockDashboardRiskOverlay(overlay.snapshot(), null));
        }
        return Map.copyOf(result);
    }
}
