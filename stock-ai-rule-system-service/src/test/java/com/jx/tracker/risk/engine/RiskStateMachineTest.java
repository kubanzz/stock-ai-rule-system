package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskStateMachineTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final LocalDate BASE_DATE = LocalDate.of(2026, 7, 18);
    private final RiskScoringEngine engine = new RiskScoringEngine();

    @Test
    void confirmsWarningAndCriticalOnTwoDistinctTradingDaysAndIsIdempotentOnRerun() {
        RiskSnapshot warningCandidate = score(
                BASE_DATE.minusDays(3), values(72, 55, 61, 48, 22), "1.05", List.of(),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot sameDayRerun = score(
                BASE_DATE.minusDays(3), values(72, 55, 61, 48, 22), "1.05",
                List.of(warningCandidate), ExtremeRiskConfirmation.none()
        );
        RiskSnapshot warning = score(
                BASE_DATE.minusDays(2), values(72, 55, 61, 48, 22), "1.05",
                List.of(warningCandidate), ExtremeRiskConfirmation.none()
        );
        RiskSnapshot criticalCandidate = score(
                BASE_DATE.minusDays(1), values(72, 55, 61, 68, 52), "1.10",
                List.of(warning), ExtremeRiskConfirmation.none()
        );
        RiskSnapshot critical = score(
                BASE_DATE, values(72, 55, 61, 68, 52), "1.10",
                List.of(criticalCandidate), ExtremeRiskConfirmation.none()
        );

        assertThat(warningCandidate.level()).isEqualTo(RiskLevel.WATCH);
        assertThat(sameDayRerun.level()).isEqualTo(RiskLevel.WATCH);
        assertThat(warning.level()).isEqualTo(RiskLevel.WARNING);
        assertThat(criticalCandidate.level()).isEqualTo(RiskLevel.WARNING);
        assertThat(critical.level()).isEqualTo(RiskLevel.CRITICAL);
    }

    @Test
    void permitsImmediateEscalationOnlyForNinetyNinthPercentileWithPriceAndFundConfirmation() {
        Map<RiskDimension, BigDecimal> criticalValues = values(72, 55, 61, 68, 52);

        RiskSnapshot immediate = score(
                BASE_DATE, criticalValues, "1.10", List.of(),
                new ExtremeRiskConfirmation(new BigDecimal("99"), true, true)
        );
        RiskSnapshot missingFundConfirmation = score(
                BASE_DATE, criticalValues, "1.10", List.of(),
                new ExtremeRiskConfirmation(new BigDecimal("99"), true, false)
        );
        RiskSnapshot belowExtremePercentile = score(
                BASE_DATE, criticalValues, "1.10", List.of(),
                new ExtremeRiskConfirmation(new BigDecimal("98.99"), true, true)
        );

        assertThat(immediate.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(missingFundConfirmation.level()).isEqualTo(RiskLevel.WATCH);
        assertThat(belowExtremePercentile.level()).isEqualTo(RiskLevel.WATCH);
    }

    @Test
    void doesNotUseAHistoricalSnapshotCalculatedAfterTheCurrentAsOf() {
        RiskSnapshot priorCandidate = score(
                BASE_DATE.minusDays(1), values(72, 55, 61, 48, 22), "1.05", List.of(),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot futureCalculatedHistory = new RiskSnapshot(
                priorCandidate.object(), priorCandidate.horizon(), priorCandidate.tradeDate(),
                priorCandidate.vScore(), priorCandidate.tScore(), priorCandidate.sScore(),
                priorCandidate.cScore(), priorCandidate.aScore(), priorCandidate.mScore(),
                priorCandidate.totalScore(), priorCandidate.level(), priorCandidate.stage(),
                priorCandidate.completeness(), priorCandidate.riskConfidence(), priorCandidate.evidence(),
                priorCandidate.modelVersion(), BASE_DATE.atTime(16, 1)
        );

        RiskSnapshot result = score(
                BASE_DATE, values(72, 55, 61, 48, 22), "1.05",
                List.of(futureCalculatedHistory), ExtremeRiskConfirmation.none()
        );

        assertThat(result.level()).isEqualTo(RiskLevel.WATCH);
    }

    @Test
    void usesTheLatestCalculatedVersionOfThePreviousTradingDayRegardlessOfInputOrder() {
        LocalDate previousTradeDate = BASE_DATE.minusDays(1);
        RiskSnapshot olderWarningCandidate = score(
                previousTradeDate, values(72, 55, 61, 48, 22), "1.05", List.of(),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot latestNormal = withCalculatedAt(
                score(
                        previousTradeDate, values(40, 20, 20, 20, 20), "1.00", List.of(),
                        ExtremeRiskConfirmation.none()
                ),
                previousTradeDate.atTime(16, 30)
        );

        RiskSnapshot latestFirst = score(
                BASE_DATE, values(72, 55, 61, 48, 22), "1.05",
                List.of(latestNormal, olderWarningCandidate), ExtremeRiskConfirmation.none()
        );
        RiskSnapshot latestLast = score(
                BASE_DATE, values(72, 55, 61, 48, 22), "1.05",
                List.of(olderWarningCandidate, latestNormal), ExtremeRiskConfirmation.none()
        );

        assertThat(latestFirst.level()).isEqualTo(RiskLevel.WATCH);
        assertThat(latestLast.level()).isEqualTo(RiskLevel.WATCH);
    }

    @Test
    void doesNotConfirmAcrossAGapWhenThePreviousTradingDaySnapshotIsMissing() {
        RiskSnapshot staleCandidate = score(
                BASE_DATE.minusDays(3), values(72, 55, 61, 48, 22), "1.05", List.of(),
                ExtremeRiskConfirmation.none()
        );

        RiskSnapshot result = score(
                BASE_DATE, values(72, 55, 61, 48, 22), "1.05",
                List.of(staleCandidate), ExtremeRiskConfirmation.none()
        );

        assertThat(result.level()).isEqualTo(RiskLevel.WATCH);
    }

    @Test
    void exitsCriticalOnlyAfterTwoDaysFivePointsBelowAndPublishesEasingFirst() {
        RiskSnapshot criticalCandidate = score(
                BASE_DATE.minusDays(1), values(72, 55, 61, 68, 52), "1.10", List.of(),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot critical = score(
                BASE_DATE, values(72, 55, 61, 68, 52), "1.10", List.of(criticalCandidate),
                ExtremeRiskConfirmation.none()
        );

        RiskSnapshot easingDayOne = score(
                BASE_DATE.plusDays(1), values(72, 55, 61, 55, 52), "1.10", List.of(critical),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot easingDayOneRerun = score(
                BASE_DATE.plusDays(1), values(72, 55, 61, 55, 52), "1.10",
                List.of(critical, easingDayOne), ExtremeRiskConfirmation.none()
        );
        RiskSnapshot easingWithExtremeFlags = score(
                BASE_DATE.plusDays(1), values(72, 55, 61, 55, 52), "1.10", List.of(critical),
                new ExtremeRiskConfirmation(new BigDecimal("99"), true, true)
        );
        RiskSnapshot easingDayTwo = score(
                BASE_DATE.plusDays(2), values(72, 55, 61, 55, 52), "1.10", List.of(easingDayOne),
                ExtremeRiskConfirmation.none()
        );

        assertThat(critical.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(easingDayOne.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(easingDayOne.stage()).isEqualTo(RiskStage.EASING);
        assertThat(easingDayOneRerun.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(easingDayOneRerun.stage()).isEqualTo(RiskStage.EASING);
        assertThat(easingWithExtremeFlags.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(easingWithExtremeFlags.stage()).isEqualTo(RiskStage.EASING);
        assertThat(easingDayTwo.level()).isEqualTo(RiskLevel.WARNING);
        assertThat(easingDayTwo.stage()).isEqualTo(RiskStage.REPRICING);
    }

    @Test
    void exitsWarningOnlyAfterTwoDaysFivePointsBelow() {
        RiskSnapshot warningCandidate = score(
                BASE_DATE.minusDays(1), values(72, 55, 61, 48, 22), "1.05", List.of(),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot warning = score(
                BASE_DATE, values(72, 55, 61, 48, 22), "1.05", List.of(warningCandidate),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot easingDayOne = score(
                BASE_DATE.plusDays(1), values(55, 55, 61, 48, 22), "1.05", List.of(warning),
                ExtremeRiskConfirmation.none()
        );
        RiskSnapshot easingDayTwo = score(
                BASE_DATE.plusDays(2), values(55, 55, 61, 48, 22), "1.05", List.of(easingDayOne),
                ExtremeRiskConfirmation.none()
        );

        assertThat(easingDayOne.level()).isEqualTo(RiskLevel.WARNING);
        assertThat(easingDayOne.stage()).isEqualTo(RiskStage.EASING);
        assertThat(easingDayTwo.level()).isEqualTo(RiskLevel.NORMAL);
    }

    private RiskSnapshot score(
            LocalDate tradeDate,
            Map<RiskDimension, BigDecimal> scores,
            String timeCorrectionFactor,
            List<RiskSnapshot> history,
            ExtremeRiskConfirmation extremeConfirmation
    ) {
        LocalDateTime asOf = tradeDate.atTime(16, 0);
        return engine.score(new RiskScoreRequest(
                MARKET,
                RiskHorizon.SHORT_TERM,
                tradeDate,
                tradeDate.minusDays(1),
                asOf,
                new BigDecimal(timeCorrectionFactor),
                evidence(scores, asOf),
                history,
                extremeConfirmation,
                "risk-engine-test-v1"
        )).snapshot();
    }

    private Map<RiskDimension, BigDecimal> values(int v, int t, int s, int c, int a) {
        Map<RiskDimension, BigDecimal> values = new EnumMap<>(RiskDimension.class);
        values.put(RiskDimension.STRUCTURAL_FRAGILITY, BigDecimal.valueOf(v));
        values.put(RiskDimension.SUBSTANTIVE_TRIGGER, BigDecimal.valueOf(t));
        values.put(RiskDimension.EXTERNAL_TRANSMISSION, BigDecimal.valueOf(s));
        values.put(RiskDimension.LOCAL_CONFIRMATION, BigDecimal.valueOf(c));
        values.put(RiskDimension.FORCED_SELLING, BigDecimal.valueOf(a));
        return values;
    }

    private List<RiskEvidence> evidence(
            Map<RiskDimension, BigDecimal> dimensionScores,
            LocalDateTime asOf
    ) {
        List<RiskEvidence> evidence = new ArrayList<>();
        for (RiskIndicatorDefinition definition : RiskIndicatorCatalog.definitions()) {
            BigDecimal score = dimensionScores.get(definition.dimension());
            evidence.add(new RiskEvidence(
                    definition.dimension(), definition.code(), score, score,
                    asOf.minusHours(1), asOf.minusMinutes(30), "test",
                    RiskDataQualityStatus.AVAILABLE, Map.of()
            ));
        }
        return evidence;
    }

    private RiskSnapshot withCalculatedAt(RiskSnapshot source, LocalDateTime calculatedAt) {
        return new RiskSnapshot(
                source.object(), source.horizon(), source.tradeDate(),
                source.vScore(), source.tScore(), source.sScore(), source.cScore(), source.aScore(),
                source.mScore(), source.totalScore(), source.level(), source.stage(),
                source.completeness(), source.riskConfidence(), source.evidence(), source.modelVersion(),
                calculatedAt
        );
    }
}
