package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskObservation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskObservationWindowIndexTest {

    @Test
    void capsEachIndicatorTraversalAtFiveYearTradingWindow() {
        RiskObjectKey stock = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        LocalDateTime asOf = tradeDate.atTime(20, 0);
        List<RiskObservation> observations = new ArrayList<>();
        for (int day = 0; day < 5_000; day++) {
            LocalDate date = tradeDate.minusDays(day);
            observations.add(new RiskObservation(
                    stock, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                    "V1", BigDecimal.valueOf(5_000L - day), "score",
                    date.atTime(18, 0), date.atTime(19, 0), "scale-fixture",
                    RiskDataQualityStatus.AVAILABLE, Map.of()));
        }

        RiskObservationWindowIndex index = new RiskObservationWindowIndex(observations);

        List<RiskObservation> window = index.window(stock, RiskHorizon.SHORT_TERM, tradeDate, asOf);

        assertThat(window).hasSize(1_250);
        assertThat(index.lastVisitedTradingDays()).isLessThanOrEqualTo(1_250);
        assertThat(window).allMatch(item -> !item.tradeDate().isBefore(tradeDate.minusDays(1_249)));
    }
}
