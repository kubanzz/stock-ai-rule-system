package com.jx.tracker.risk.query;

import com.jx.tracker.risk.query.ProvisionalRiskAssessmentCalculator.Assessment;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProvisionalRiskAssessmentCalculatorTest {

    private final ProvisionalRiskAssessmentCalculator calculator =
            new ProvisionalRiskAssessmentCalculator();

    @Test
    void calculatesWatchProvisionalAssessmentFromTwoDimensions() {
        Assessment result = calculator.calculate(
                new BigDecimal("0.41"),
                null,
                null,
                false,
                List.of(
                        evidence("S1", "S", "64.9"),
                        evidence("C1", "C", "58.1")
                )
        );

        assertThat(result.conclusionStatus()).isEqualTo("provisional");
        assertThat(result.provisionalScore()).isEqualByComparingTo("61.0143");
        assertThat(result.provisionalLevel()).isEqualTo("watch");
        assertThat(result.dimensions())
                .filteredOn(item -> item.dimension().equals("S"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.usedCount()).isEqualTo(1);
                    assertThat(item.totalCount()).isEqualTo(5);
                    assertThat(item.coverage()).isEqualByComparingTo("0.30");
                });
    }

    @Test
    void keepsFormalSnapshotFieldsAuthoritativeAtEightyPercent() {
        Assessment result = calculator.calculate(
                new BigDecimal("0.80"),
                new BigDecimal("67"),
                "warning",
                false,
                List.of(evidence("V3", "V", "62"), evidence("C1", "C", "60"))
        );

        assertThat(result.conclusionStatus()).isEqualTo("formal");
        assertThat(result.provisionalScore()).isNull();
        assertThat(result.provisionalLevel()).isNull();
    }

    @Test
    void returnsInsufficientBelowTwentyPercent() {
        Assessment result = calculator.calculate(
                new BigDecimal("0.19"),
                null,
                null,
                false,
                List.of(evidence("S1", "S", "75"), evidence("C1", "C", "70"))
        );

        assertThat(result.conclusionStatus()).isEqualTo("insufficient");
        assertThat(result.provisionalScore()).isNull();
    }

    @Test
    void returnsInsufficientWhenOnlyOneDimensionIsAvailable() {
        Assessment result = calculator.calculate(
                new BigDecimal("0.25"),
                null,
                null,
                false,
                List.of(evidence("S1", "S", "75"), evidence("S2", "S", "70"))
        );

        assertThat(result.conclusionStatus()).isEqualTo("insufficient");
    }

    @Test
    void capsProvisionalLevelAtWatchWhenFormalDimensionGatesAreMissing() {
        Assessment result = calculator.calculate(
                new BigDecimal("0.45"),
                null,
                null,
                false,
                List.of(evidence("S1", "S", "95"), evidence("C1", "C", "92"))
        );

        assertThat(result.provisionalScore()).isGreaterThan(new BigDecimal("80"));
        assertThat(result.provisionalLevel()).isEqualTo("watch");
    }

    @Test
    void describesUsedAndMissingCatalogIndicators() {
        Assessment result = calculator.calculate(
                new BigDecimal("0.20"),
                null,
                null,
                false,
                List.of(evidence("V3", "V", "32"), evidence("S1", "S", "50"))
        );

        assertThat(result.dimensions())
                .filteredOn(item -> item.dimension().equals("V"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.indicators())
                            .filteredOn(indicator -> indicator.code().equals("V3"))
                            .singleElement()
                            .satisfies(indicator -> {
                                assertThat(indicator.status()).isEqualTo("used");
                                assertThat(indicator.used()).isTrue();
                            });
                    assertThat(item.indicators())
                            .filteredOn(indicator -> indicator.code().equals("V2"))
                            .singleElement()
                            .satisfies(indicator ->
                                    assertThat(indicator.status()).isEqualTo("not_integrated"));
                });
    }

    @Test
    void reportsUnavailableWhenNoUsableEvidenceExists() {
        RiskEvidence failed = new RiskEvidence(
                "V",
                "V1",
                null,
                null,
                LocalDateTime.of(2026, 7, 24, 18, 0),
                LocalDateTime.of(2026, 7, 24, 20, 0),
                "aktools",
                "unavailable",
                Map.of()
        );

        Assessment result = calculator.calculate(
                BigDecimal.ZERO, null, null, true, List.of(failed));

        assertThat(result.conclusionStatus()).isEqualTo("unavailable");
    }

    private RiskEvidence evidence(String code, String dimension, String score) {
        return new RiskEvidence(
                dimension,
                code,
                new BigDecimal(score),
                new BigDecimal(score),
                LocalDateTime.of(2026, 7, 24, 18, 0),
                LocalDateTime.of(2026, 7, 24, 20, 0),
                "fixture",
                "available",
                Map.of()
        );
    }
}
