package com.jx.tracker.risk.data.market;

import java.math.BigDecimal;

public record MarketBreadth(
        BigDecimal advanceRatio,
        BigDecimal newHighLowBalance,
        BigDecimal aboveMovingAverageRatio,
        BigDecimal declineRatio,
        BigDecimal newLowRatio,
        BigDecimal belowMovingAverageRatio
) {
}
