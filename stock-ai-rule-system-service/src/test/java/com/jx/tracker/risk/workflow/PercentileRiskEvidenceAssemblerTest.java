package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.engine.RiskIndicatorComponentCatalog;
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
        for (int index = 0; index < 1_250; index++) {
            LocalDate date = tradeDate.minusDays(1_249L - index);
            history.add(observation(object, date, BigDecimal.valueOf(index % 5 + 1L), date.atTime(18, 0)));
        }
        history.add(observation(object, tradeDate, new BigDecimal("999"), asOf.plusMinutes(1)));

        List<RiskEvidence> evidence = new PercentileRiskEvidenceAssembler(new RiskNormalizer())
                .assemble(object, RiskHorizon.SHORT_TERM, tradeDate, asOf, history);

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.indicatorCode()).isEqualTo("V3");
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
        for (int index = 0; index < 1_250; index++) {
            LocalDate date = tradeDate.minusDays(1_249L - index);
            history.add(new RiskObservation(
                    object, RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION,
                    "C1", BigDecimal.valueOf(index % 5 + 1L), "score",
                    date.atTime(18, 0), date.atTime(19, 0), "price-source",
                    RiskDataQualityStatus.AVAILABLE,
                    Map.of("tradingDay", true, "metric", "leaderRelativeReturn")));
            history.add(new RiskObservation(
                    object, RiskHorizon.SHORT_TERM, date, RiskDimension.FORCED_SELLING,
                    "A2", BigDecimal.valueOf(index % 5 + 1L), "score",
                    date.atTime(18, 0), date.atTime(19, 0), "fund-source",
                    RiskDataQualityStatus.AVAILABLE, Map.of()));
        }

        List<RiskEvidence> evidence = new PercentileRiskEvidenceAssembler(new RiskNormalizer())
                .assemble(object, RiskHorizon.SHORT_TERM, tradeDate, asOf, history);

        assertThat(evidence).hasSize(2).allSatisfy(item -> assertThat(item.details())
                .containsEntry("tradeDate", tradeDate.toString())
                .containsEntry("extremeCandidate", true));
    }

    @Test
    void preservesCompositeComponentsAndOrientsWorseInputsTowardHigherRisk() {
        RiskObjectKey object = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        LocalDateTime asOf = tradeDate.atTime(20, 0);
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 0; index < 1_250; index++) {
            LocalDate date = tradeDate.minusDays(1_249L - index);
            history.add(component(object, date, "V1", "peTtm", BigDecimal.valueOf(index + 1L)));
            history.add(component(object, date, "V1", "riskPremium", BigDecimal.valueOf(1_250L - index)));
        }

        List<RiskEvidence> evidence = new PercentileRiskEvidenceAssembler(new RiskNormalizer())
                .assemble(object, RiskHorizon.SHORT_TERM, tradeDate, asOf, history);

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.score()).isEqualByComparingTo("99.9600");
            assertThat(item.details()).containsKeys("components", "componentDirections");
            assertThat(item.details().get("components").toString())
                    .contains("peTtm", "riskPremium", "source-a", "observedAt", "availableAt");
        });
    }

    @Test
    void orientsWeakLocalAndLeadingReturnsAsHigherRisk() {
        assertThat(RiskIndicatorComponentCatalog.direction("C1", "leaderRelativeReturn"))
                .isEqualTo(RiskIndicatorComponentCatalog.RiskDirection.DECREASE_IS_RISK);
        assertThat(RiskIndicatorComponentCatalog.direction("C4", "relativeStrength"))
                .isEqualTo(RiskIndicatorComponentCatalog.RiskDirection.DECREASE_IS_RISK);
        assertThat(RiskIndicatorComponentCatalog.direction("S1", "standardizedLeadingReturn"))
                .isEqualTo(RiskIndicatorComponentCatalog.RiskDirection.DECREASE_IS_RISK);
    }

    @Test
    void scoresEveryDocumentedCompositeTowardHigherRiskWhenAllComponentsWorsen() {
        assertCompositeRisk("C2", RiskDimension.LOCAL_CONFIRMATION,
                List.of("advanceRatio", "newHighLowBalance", "aboveMovingAverageRatio"), false);
        assertCompositeRisk("C5", RiskDimension.LOCAL_CONFIRMATION,
                List.of("trendDistance", "openingGap"), false);
        assertCompositeRisk("A4", RiskDimension.FORCED_SELLING,
                List.of("declineRatio", "newLowRatio", "belowMovingAverageRatio"), true);
    }

    private void assertCompositeRisk(
            String indicator,
            RiskDimension dimension,
            List<String> components,
            boolean increasingRisk
    ) {
        RiskObjectKey object = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 0; index < 1_250; index++) {
            LocalDate date = tradeDate.minusDays(1_249L - index);
            BigDecimal value = BigDecimal.valueOf(increasingRisk ? index + 1L : 1_250L - index);
            for (String component : components) {
                history.add(new RiskObservation(
                        object, RiskHorizon.SHORT_TERM, date, dimension, indicator, component,
                        value, "ratio", date.atTime(18, 0), date.atTime(19, 0), "source-a",
                        RiskDataQualityStatus.AVAILABLE, Map.of("metric", component)));
            }
        }

        RiskEvidence result = new PercentileRiskEvidenceAssembler(new RiskNormalizer())
                .assemble(object, RiskHorizon.SHORT_TERM, tradeDate, tradeDate.atTime(20, 0), history)
                .getFirst();

        assertThat(result.score()).isGreaterThan(new BigDecimal("99.90"));
        components.forEach(component ->
                assertThat(result.details().get("components").toString()).contains(component));
    }

    private RiskObservation component(
            RiskObjectKey object,
            LocalDate date,
            String indicator,
            String component,
            BigDecimal value
    ) {
        return new RiskObservation(
                object, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                indicator, value, "ratio", date.atTime(18, 0), date.atTime(19, 0),
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of("metric", component));
    }

    private RiskObservation observation(
            RiskObjectKey object,
            LocalDate date,
            BigDecimal value,
            LocalDateTime availableAt
    ) {
        return new RiskObservation(
                object, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                "V3", value, "ratio", availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }
}
