package com.jx.tracker.risk.gate;

import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.SignalDirection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** 待进行影子闸门评估的正式信号；方向是只读输入，闸门不得改写。 */
public record RiskSignalCandidate(
        String signalReference,
        RiskObjectKey signalObject,
        RiskHorizon horizon,
        LocalDate tradeDate,
        SignalDirection direction,
        BigDecimal originalConfidence,
        List<RiskObjectKey> relevantRiskObjects
) {

    public RiskSignalCandidate {
        if (signalReference == null || signalReference.isBlank()) {
            throw new IllegalArgumentException("signalReference must not be blank");
        }
        if (signalObject == null || horizon == null || tradeDate == null || direction == null
                || originalConfidence == null) {
            throw new IllegalArgumentException("signal object, horizon, date, direction and confidence are required");
        }
        if (originalConfidence.signum() < 0 || originalConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("originalConfidence must be between 0 and 1");
        }
        relevantRiskObjects = relevantRiskObjects == null || relevantRiskObjects.isEmpty()
                ? List.of(signalObject)
                : List.copyOf(relevantRiskObjects);
    }

    public static String stockSignalReference(String symbol, LocalDate tradeDate) {
        if (symbol == null || symbol.isBlank() || tradeDate == null) {
            throw new IllegalArgumentException("symbol and tradeDate are required");
        }
        return "signal:" + symbol.trim().toUpperCase(Locale.ROOT) + ":" + tradeDate;
    }

    /** 方案信号使用自己的稳定 id；legacy 继续读取迁移前的风险建议。 */
    public static String stockSignalReference(Long signalId, String symbol, LocalDate tradeDate,
                                              String strategyCode, String strategyVersion) {
        if ((strategyCode == null || StockSignalDaily.LEGACY_STRATEGY_CODE.equals(strategyCode))
                && (strategyVersion == null || StockSignalDaily.LEGACY_STRATEGY_VERSION.equals(strategyVersion))) {
            return stockSignalReference(symbol, tradeDate);
        }
        if (signalId == null || signalId <= 0) {
            throw new IllegalArgumentException("persisted strategy signal id is required");
        }
        return "signal:id:" + signalId;
    }
}
