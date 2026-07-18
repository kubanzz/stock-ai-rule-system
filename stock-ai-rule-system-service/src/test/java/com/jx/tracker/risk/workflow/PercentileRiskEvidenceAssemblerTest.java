package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
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

class PercentileRiskEvidenceAssemblerTest {

    @Test
    void buildsFiveDayRollingPercentileWithoutFutureAvailableRevision() {
        RiskObjectKey object = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        LocalDateTime asOf = tradeDate.atTime(20, 0);
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 4; index >= 0; index--) {
            LocalDate date = tradeDate.minusDays(index);
            history.add(observation(object, date, BigDecimal.valueOf(5 - index), date.atTime(18, 0)));
        }
        history.add(observation(object, tradeDate, new BigDecimal("999"), asOf.plusMinutes(1)));

        List<RiskEvidence> evidence = new PercentileRiskEvidenceAssembler(new RiskNormalizer())
                .assemble(object, RiskHorizon.SHORT_TERM, tradeDate, asOf, history);

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.indicatorCode()).isEqualTo("V1");
            assertThat(item.rawValue()).isEqualByComparingTo("5");
            assertThat(item.score()).isEqualByComparingTo("100.0000");
            assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
            assertThat(item.availableAt()).isBeforeOrEqualTo(asOf);
            assertThat(item.details())
                    .containsEntry("tradeDate", tradeDate.toString())
                    .containsEntry("extremeCandidate", false);
        });
    }

    @Test
    void marksOnlyCurrentPriceAndFundDimensionsAsExplicitExtremeCandidates() {
        RiskObjectKey object = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        LocalDateTime asOf = tradeDate.atTime(20, 0);
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 4; index >= 0; index--) {
            LocalDate date = tradeDate.minusDays(index);
            history.add(new RiskObservation(
                    object, RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION,
                    "C1", BigDecimal.valueOf(5 - index), "score",
                    date.atTime(18, 0), date.atTime(19, 0), "price-source",
                    RiskDataQualityStatus.AVAILABLE, Map.of("tradingDay", true)));
            history.add(new RiskObservation(
                    object, RiskHorizon.SHORT_TERM, date, RiskDimension.FORCED_SELLING,
                    "A2", BigDecimal.valueOf(5 - index), "score",
                    date.atTime(18, 0), date.atTime(19, 0), "fund-source",
                    RiskDataQualityStatus.AVAILABLE, Map.of()));
        }

        List<RiskEvidence> evidence = new PercentileRiskEvidenceAssembler(new RiskNormalizer())
                .assemble(object, RiskHorizon.SHORT_TERM, tradeDate, asOf, history);

        assertThat(evidence).hasSize(2).allSatisfy(item -> assertThat(item.details())
                .containsEntry("tradeDate", tradeDate.toString())
                .containsEntry("extremeCandidate", true));
    }

    private RiskObservation observation(
            RiskObjectKey object,
            LocalDate date,
            BigDecimal value,
            LocalDateTime availableAt
    ) {
        return new RiskObservation(
                object, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                "V1", value, "ratio", availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }
}
