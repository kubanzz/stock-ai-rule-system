package com.jx.tracker.risk.data.flow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** 只生成标准化输入代理，不计算最终风险分。 */
public final class FlowEventMetrics {

    private static final int SCALE = 8;

    private FlowEventMetrics() {
    }

    public static BigDecimal balanceLevel(BigDecimal balance, BigDecimal referenceBalance) {
        return safeRatio(balance, referenceBalance);
    }

    public static BigDecimal balanceChange(BigDecimal balance, BigDecimal previousBalance) {
        return safeRatio(balance.subtract(previousBalance), previousBalance);
    }

    public static BigDecimal deleveragingProxy(BigDecimal balance, BigDecimal previousBalance) {
        return balanceChange(balance, previousBalance).negate().max(BigDecimal.ZERO);
    }

    public static BigDecimal redemptionProxy(BigDecimal netFlow, BigDecimal referenceAssets) {
        return safeRatio(netFlow.negate().max(BigDecimal.ZERO), referenceAssets.abs());
    }

    public static List<BigDecimal> netFlowSeries(List<BigDecimal> netFlows) {
        if (netFlows == null || netFlows.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("net flow series must not contain null");
        }
        return List.copyOf(netFlows);
    }

    public static BigDecimal normalizeSeverity(BigDecimal rawSeverity) {
        if (rawSeverity == null) {
            return BigDecimal.ZERO;
        }
        return rawSeverity.max(BigDecimal.ZERO).min(new BigDecimal("100")).stripTrailingZeros();
    }

    private static BigDecimal safeRatio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
