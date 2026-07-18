package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCoverageGateTest {

    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600000.SH");
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 18, 16, 0);
    private final RiskScoringEngine engine = new RiskScoringEngine();

    @Test
    void requiresSixtyPercentValidWeightForEachDimension() {
        Map<String, RiskDataQualityStatus> overrides = Map.of(
                "V4", RiskDataQualityStatus.UNAVAILABLE,
                "V5", RiskDataQualityStatus.UNAVAILABLE,
                "V6", RiskDataQualityStatus.UNAVAILABLE
        );

        RiskScoreResult result = score(overrides, Map.of());

        assertThat(result.snapshot().completeness()).isEqualByComparingTo("0.9000");
        assertThat(result.snapshot().vScore()).isNull();
        assertFormalQuartetIsAbsent(result);
        assertThat(result.missingReasons())
                .contains(
                        "DIMENSION_VALID_WEIGHT_BELOW_60_PERCENT:V",
                        "EVIDENCE_UNAVAILABLE:V4"
                );
    }

    @Test
    void withholdsTheFormalQuartetBelowEightyPercentOverallCompleteness() {
        Map<String, RiskDataQualityStatus> overrides = Map.of(
                "V1", RiskDataQualityStatus.UNAVAILABLE,
                "T1", RiskDataQualityStatus.UNAVAILABLE,
                "S1", RiskDataQualityStatus.UNAVAILABLE,
                "A1", RiskDataQualityStatus.UNAVAILABLE
        );

        RiskScoreResult result = score(overrides, Map.of());

        assertThat(result.snapshot().completeness()).isEqualByComparingTo("0.7900");
        assertFormalQuartetIsAbsent(result);
        assertThat(result.missingReasons())
                .contains("OVERALL_COMPLETENESS_BELOW_80_PERCENT");
    }

    @Test
    void acceptsExactlySixtyPercentDimensionWeightAndEightyPercentOverallCompleteness() {
        Map<String, RiskDataQualityStatus> overrides = Map.of(
                "V1", RiskDataQualityStatus.UNAVAILABLE,
                "V4", RiskDataQualityStatus.UNAVAILABLE,
                "T1", RiskDataQualityStatus.UNAVAILABLE,
                "S1", RiskDataQualityStatus.UNAVAILABLE
        );

        RiskScoreResult result = score(overrides, Map.of());

        assertThat(result.snapshot().vScore()).isEqualByComparingTo("70.0000");
        assertThat(result.snapshot().completeness()).isEqualByComparingTo("0.8000");
        assertThat(result.snapshot().totalScore()).isNotNull();
        assertThat(result.snapshot().riskConfidence()).isEqualByComparingTo("0.8000");
    }

    @Test
    void requiresRealEvidenceFromVCAAndAtLeastOneOfTOrS() {
        RiskScoreResult missingV = score(statusesForDimension(
                RiskDimension.STRUCTURAL_FRAGILITY,
                RiskDataQualityStatus.INSUFFICIENT_HISTORY
        ), Map.of());
        Map<String, RiskDataQualityStatus> missingTriggerAndTransmission = new HashMap<>();
        missingTriggerAndTransmission.putAll(statusesForDimension(
                RiskDimension.SUBSTANTIVE_TRIGGER,
                RiskDataQualityStatus.UNAVAILABLE
        ));
        missingTriggerAndTransmission.putAll(statusesForDimension(
                RiskDimension.EXTERNAL_TRANSMISSION,
                RiskDataQualityStatus.STALE
        ));
        RiskScoreResult missingTAndS = score(missingTriggerAndTransmission, Map.of());

        assertFormalQuartetIsAbsent(missingV);
        assertThat(missingV.missingReasons()).contains(
                "MISSING_REAL_EVIDENCE:V",
                "EVIDENCE_INSUFFICIENT_HISTORY:V1"
        );
        assertFormalQuartetIsAbsent(missingTAndS);
        assertThat(missingTAndS.missingReasons()).contains(
                "DIMENSION_VALID_WEIGHT_BELOW_60_PERCENT:T_OR_S",
                "MISSING_REAL_EVIDENCE:T_OR_S"
        );
    }

    @Test
    void neverTurnsUnavailableStaleOrInsufficientEvidenceIntoZero() {
        Map<String, RiskDataQualityStatus> overrides = new HashMap<>();
        overrides.put("V1", RiskDataQualityStatus.UNAVAILABLE);
        overrides.put("V2", RiskDataQualityStatus.STALE);
        overrides.put("V3", RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        overrides.put("V4", RiskDataQualityStatus.UNAVAILABLE);
        overrides.put("V5", RiskDataQualityStatus.STALE);
        overrides.put("V6", RiskDataQualityStatus.INSUFFICIENT_HISTORY);

        RiskScoreResult result = score(overrides, Map.of("V2", new BigDecimal("88"), "V5", new BigDecimal("91")));

        assertThat(result.snapshot().vScore()).isNull();
        assertThat(result.snapshot().completeness()).isEqualByComparingTo("0.8000");
        assertFormalQuartetIsAbsent(result);
    }

    @Test
    void usesOnlyEvidenceAvailableByAsOfAndTreatsANewStockAsInsufficient() {
        List<RiskEvidence> futureOnly = evidence(Map.of(), Map.of()).stream()
                .map(item -> new RiskEvidence(
                        item.dimension(), item.indicatorCode(), item.score(), item.rawValue(),
                        item.observedAt(), AS_OF.plusMinutes(1), item.source(), item.qualityStatus(), item.details()
                ))
                .toList();

        RiskScoreResult futureResult = engine.score(request(futureOnly));
        RiskScoreResult newStockResult = engine.score(request(List.of()));

        assertThat(futureResult.snapshot().completeness()).isEqualByComparingTo("0.0000");
        assertFormalQuartetIsAbsent(futureResult);
        assertThat(newStockResult.snapshot().completeness()).isEqualByComparingTo("0.0000");
        assertFormalQuartetIsAbsent(newStockResult);
    }

    @Test
    void representsACompleteProviderFailureAsMissingDataRatherThanAZeroRiskScore() {
        RiskScoreResult result = score(
                RiskIndicatorCatalog.definitions().stream().collect(java.util.stream.Collectors.toMap(
                        RiskIndicatorDefinition::code,
                        ignored -> RiskDataQualityStatus.UNAVAILABLE
                )),
                Map.of()
        );

        assertThat(result.snapshot().completeness()).isEqualByComparingTo("0.0000");
        assertThat(result.snapshot().vScore()).isNull();
        assertThat(result.snapshot().tScore()).isNull();
        assertThat(result.snapshot().sScore()).isNull();
        assertThat(result.snapshot().cScore()).isNull();
        assertThat(result.snapshot().aScore()).isNull();
        assertFormalQuartetIsAbsent(result);
    }

    @Test
    void definesRiskConfidenceAsFreshEvidenceCompleteness() {
        RiskScoreResult complete = score(Map.of(), Map.of());
        RiskScoreResult oneStaleIndicator = score(
                Map.of("S5", RiskDataQualityStatus.STALE),
                Map.of("S5", new BigDecimal("75"))
        );

        assertThat(complete.snapshot().riskConfidence()).isEqualByComparingTo("1.0000");
        assertThat(oneStaleIndicator.snapshot().completeness()).isEqualByComparingTo("0.9800");
        assertThat(oneStaleIndicator.snapshot().riskConfidence()).isEqualByComparingTo("0.9800");
    }

    private RiskScoreResult score(
            Map<String, RiskDataQualityStatus> qualityOverrides,
            Map<String, BigDecimal> retainedValues
    ) {
        return engine.score(request(evidence(qualityOverrides, retainedValues)));
    }

    private RiskScoreRequest request(List<RiskEvidence> evidence) {
        return new RiskScoreRequest(
                STOCK,
                RiskHorizon.SHORT_TERM,
                TRADE_DATE,
                TRADE_DATE.minusDays(1),
                AS_OF,
                BigDecimal.ONE,
                evidence,
                List.of(),
                ExtremeRiskConfirmation.none(),
                "risk-engine-test-v1"
        );
    }

    private List<RiskEvidence> evidence(
            Map<String, RiskDataQualityStatus> qualityOverrides,
            Map<String, BigDecimal> retainedValues
    ) {
        List<RiskEvidence> evidence = new ArrayList<>();
        for (RiskIndicatorDefinition definition : RiskIndicatorCatalog.definitions()) {
            RiskDataQualityStatus quality = qualityOverrides.getOrDefault(
                    definition.code(),
                    RiskDataQualityStatus.AVAILABLE
            );
            BigDecimal value = retainedValues.getOrDefault(definition.code(), new BigDecimal("70"));
            boolean mustBeNull = quality == RiskDataQualityStatus.UNAVAILABLE
                    || quality == RiskDataQualityStatus.INSUFFICIENT_HISTORY;
            evidence.add(new RiskEvidence(
                    definition.dimension(),
                    definition.code(),
                    mustBeNull ? null : value,
                    mustBeNull ? null : value,
                    AS_OF.minusHours(1),
                    AS_OF.minusMinutes(30),
                    "test",
                    quality,
                    Map.of()
            ));
        }
        return evidence;
    }

    private Map<String, RiskDataQualityStatus> statusesForDimension(
            RiskDimension dimension,
            RiskDataQualityStatus quality
    ) {
        Map<String, RiskDataQualityStatus> statuses = new HashMap<>();
        for (RiskIndicatorDefinition definition : RiskIndicatorCatalog.forDimension(dimension)) {
            statuses.put(definition.code(), quality);
        }
        return statuses;
    }

    private void assertFormalQuartetIsAbsent(RiskScoreResult result) {
        assertThat(result.snapshot().totalScore()).isNull();
        assertThat(result.snapshot().level()).isNull();
        assertThat(result.snapshot().stage()).isNull();
        assertThat(result.snapshot().riskConfidence()).isNull();
    }
}
