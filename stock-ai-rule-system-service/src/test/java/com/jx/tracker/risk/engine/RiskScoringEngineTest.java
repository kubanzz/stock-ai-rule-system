package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoringEngineTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 18, 16, 0);
    private final RiskScoringEngine engine = new RiskScoringEngine();

    @Test
    void reproducesTheThreeDocumentedScenariosForEveryHorizon() {
        for (RiskHorizon horizon : RiskHorizon.values()) {
            RiskScoreResult watch = score(
                    horizon, scores(72, 35, 68, 28, 15), "1.00", List.of()
            );
            assertThat(watch.snapshot().totalScore()).isEqualByComparingTo("46.6500");
            assertThat(watch.snapshot().level()).isEqualTo(RiskLevel.WATCH);

            RiskScoreResult warningDayOne = scoreOn(
                    horizon, TRADE_DATE.minusDays(1), AS_OF.minusDays(1),
                    scores(72, 55, 61, 48, 22), "1.05", List.of()
            );
            RiskScoreResult warning = score(
                    horizon, scores(72, 55, 61, 48, 22), "1.05",
                    List.of(warningDayOne.snapshot())
            );
            assertThat(warning.snapshot().totalScore()).isEqualByComparingTo("57.3825");
            assertThat(warning.snapshot().level()).isEqualTo(RiskLevel.WARNING);

            RiskScoreResult criticalDayOne = scoreOn(
                    horizon, TRADE_DATE.minusDays(1), AS_OF.minusDays(1),
                    scores(72, 55, 61, 68, 52), "1.10", List.of()
            );
            RiskScoreResult critical = score(
                    horizon, scores(72, 55, 61, 68, 52), "1.10",
                    List.of(criticalDayOne.snapshot())
            );
            assertThat(critical.snapshot().totalScore()).isEqualByComparingTo("69.4650");
            assertThat(critical.snapshot().level()).isEqualTo(RiskLevel.CRITICAL);
        }
    }

    @Test
    void appliesTheFixedFormulaAndCapsTheScoreAtOneHundred() {
        RiskScoreResult result = score(
                RiskHorizon.SHORT_TERM, scores(100, 100, 100, 100, 100), "1.20", List.of()
        );

        assertThat(result.snapshot().vScore()).isEqualByComparingTo("100.0000");
        assertThat(result.snapshot().mScore()).isEqualByComparingTo("1.20");
        assertThat(result.snapshot().totalScore()).isEqualByComparingTo("100.0000");
    }

    @Test
    void appliesTheExactWatchWarningAndCriticalGateBoundaries() {
        assertThat(scoreImmediate(
                scores("60", "49.9999", "49.9999", "39.9999", "39.9999"), "1.00"
        ).snapshot().level()).isEqualTo(RiskLevel.WATCH);
        assertThat(scoreImmediate(
                scores("59.9999", "80", "80", "80", "80"), "1.20"
        ).snapshot().level()).isEqualTo(RiskLevel.NORMAL);

        assertThat(scoreImmediate(
                scores("60", "50", "49.9999", "40", "0"), "1.00"
        ).snapshot().level()).isEqualTo(RiskLevel.WARNING);
        assertThat(scoreImmediate(
                scores("60", "50", "49.9999", "39.9999", "80"), "1.20"
        ).snapshot().level()).isEqualTo(RiskLevel.WATCH);

        assertThat(scoreImmediate(
                scores("72", "55", "61", "68", "52"), "1.0292953286"
        ).snapshot().level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(scoreImmediate(
                scores("72", "55", "61", "68", "52"), "1.02929"
        ).snapshot().level()).isEqualTo(RiskLevel.WARNING);
        assertThat(scoreImmediate(
                scores("72", "55", "61", "59.9999", "52"), "1.10"
        ).snapshot().level()).isEqualTo(RiskLevel.WARNING);
        assertThat(scoreImmediate(
                scores("72", "55", "61", "68", "39.9999"), "1.10"
        ).snapshot().level()).isEqualTo(RiskLevel.WARNING);
    }

    @Test
    void treatsTriggerAndExternalTransmissionAsSubstituteGatesWithoutReweighting() {
        Map<RiskDimension, BigDecimal> scores = scores(80, 60, 70, 80, 60);

        RiskScoreResult withoutTrigger = scoreWithoutDimension(
                RiskDimension.SUBSTANTIVE_TRIGGER, scores);
        RiskScoreResult withoutTransmission = scoreWithoutDimension(
                RiskDimension.EXTERNAL_TRANSMISSION, scores);

        assertThat(withoutTrigger.missingReasons()).doesNotContain(
                "DIMENSION_VALID_WEIGHT_BELOW_60_PERCENT:T", "MISSING_REAL_EVIDENCE:T_OR_S");
        assertThat(withoutTrigger.snapshot().totalScore()).isEqualByComparingTo("59.5000");
        assertThat(withoutTrigger.snapshot().level()).isEqualTo(RiskLevel.WARNING);
        assertThat(withoutTransmission.missingReasons()).doesNotContain(
                "DIMENSION_VALID_WEIGHT_BELOW_60_PERCENT:S", "MISSING_REAL_EVIDENCE:T_OR_S");
        assertThat(withoutTransmission.snapshot().totalScore()).isEqualByComparingTo("61.0000");
        assertThat(withoutTransmission.snapshot().level()).isEqualTo(RiskLevel.WARNING);
    }

    private RiskScoreResult scoreWithoutDimension(
            RiskDimension missing,
            Map<RiskDimension, BigDecimal> scores
    ) {
        List<RiskEvidence> evidence = evidence(scores, AS_OF).stream()
                .filter(item -> item.dimension() != missing)
                .toList();
        return engine.score(new RiskScoreRequest(
                MARKET, RiskHorizon.SHORT_TERM, TRADE_DATE, TRADE_DATE.minusDays(1), AS_OF,
                BigDecimal.ONE, evidence, List.of(),
                new ExtremeRiskConfirmation(new BigDecimal("99"), true, true), "risk-engine-test-v1"));
    }

    private RiskScoreResult score(
            RiskHorizon horizon,
            Map<RiskDimension, BigDecimal> scores,
            String timeCorrectionFactor,
            List<com.jx.tracker.risk.model.RiskSnapshot> history
    ) {
        return scoreOn(horizon, TRADE_DATE, AS_OF, scores, timeCorrectionFactor, history);
    }

    private RiskScoreResult scoreOn(
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf,
            Map<RiskDimension, BigDecimal> scores,
            String timeCorrectionFactor,
            List<com.jx.tracker.risk.model.RiskSnapshot> history
    ) {
        return scoreOn(
                horizon, tradeDate, asOf, scores, timeCorrectionFactor, history,
                ExtremeRiskConfirmation.none()
        );
    }

    private RiskScoreResult scoreOn(
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf,
            Map<RiskDimension, BigDecimal> scores,
            String timeCorrectionFactor,
            List<com.jx.tracker.risk.model.RiskSnapshot> history,
            ExtremeRiskConfirmation extremeConfirmation
    ) {
        return engine.score(new RiskScoreRequest(
                MARKET,
                horizon,
                tradeDate,
                tradeDate.minusDays(1),
                asOf,
                new BigDecimal(timeCorrectionFactor),
                evidence(scores, asOf),
                history,
                extremeConfirmation,
                "risk-engine-test-v1"
        ));
    }

    private RiskScoreResult scoreImmediate(
            Map<RiskDimension, BigDecimal> scores,
            String timeCorrectionFactor
    ) {
        return scoreOn(
                RiskHorizon.SHORT_TERM, TRADE_DATE, AS_OF, scores, timeCorrectionFactor, List.of(),
                new ExtremeRiskConfirmation(new BigDecimal("99"), true, true)
        );
    }

    private Map<RiskDimension, BigDecimal> scores(int v, int t, int s, int c, int a) {
        return scores(
                Integer.toString(v), Integer.toString(t), Integer.toString(s),
                Integer.toString(c), Integer.toString(a)
        );
    }

    private Map<RiskDimension, BigDecimal> scores(String v, String t, String s, String c, String a) {
        Map<RiskDimension, BigDecimal> scores = new EnumMap<>(RiskDimension.class);
        scores.put(RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal(v));
        scores.put(RiskDimension.SUBSTANTIVE_TRIGGER, new BigDecimal(t));
        scores.put(RiskDimension.EXTERNAL_TRANSMISSION, new BigDecimal(s));
        scores.put(RiskDimension.LOCAL_CONFIRMATION, new BigDecimal(c));
        scores.put(RiskDimension.FORCED_SELLING, new BigDecimal(a));
        return scores;
    }

    private List<RiskEvidence> evidence(
            Map<RiskDimension, BigDecimal> dimensionScores,
            LocalDateTime asOf
    ) {
        List<RiskEvidence> evidence = new ArrayList<>();
        for (RiskIndicatorDefinition definition : RiskIndicatorCatalog.definitions()) {
            BigDecimal score = dimensionScores.get(definition.dimension());
            evidence.add(new RiskEvidence(
                    definition.dimension(),
                    definition.code(),
                    score,
                    score,
                    asOf.minusHours(1),
                    asOf.minusMinutes(30),
                    "test",
                    RiskDataQualityStatus.AVAILABLE,
                    Map.of()
            ));
        }
        return evidence;
    }
}
