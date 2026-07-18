package com.jx.tracker.risk.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskContractsTest {

    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 7, 18, 15, 0);
    private static final LocalDateTime AVAILABLE_AT = LocalDateTime.of(2026, 7, 18, 16, 0);

    @Test
    void snapshotKeepsRiskScoresSeparateFromConfidenceAndAllowsMissingScores() throws Exception {
        RiskEvidence unavailableEvidence = new RiskEvidence(
                RiskDimension.CONTAGION,
                "northbound_flow",
                null,
                null,
                OBSERVED_AT,
                AVAILABLE_AT,
                "aktools",
                RiskDataQualityStatus.UNAVAILABLE,
                Map.of("reason", "source_timeout")
        );
        RiskSnapshot snapshot = new RiskSnapshot(
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                new BigDecimal("72.50"),
                new BigDecimal("61.25"),
                null,
                null,
                new BigDecimal("80.00"),
                new BigDecimal("1.05"),
                null,
                null,
                null,
                new BigDecimal("0.58"),
                null,
                List.of(unavailableEvidence),
                "risk-v1",
                AVAILABLE_AT
        );

        assertThat(snapshot.totalScore()).isNull();
        assertThat(snapshot.level()).isNull();
        assertThat(snapshot.evidence()).containsExactly(unavailableEvidence);
        assertThat(RiskDecisionSupportNotice.TEXT)
                .contains("辅助决策")
                .contains("不构成投资建议")
                .doesNotContain("暴跌概率");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(snapshot);
        assertThat(json).contains(
                        "\"objectType\":\"stock\"",
                        "\"horizon\":\"1-5d\"",
                        "\"riskConfidence\":null",
                        "\"calculatedAt\""
                )
                .doesNotContain("risk_probability", "riskProbability");
    }

    @Test
    void validatesScoreAndRatioBoundaries() {
        assertThatThrownBy(() -> completeSnapshot(new BigDecimal("100.01"), new BigDecimal("0.80"), new BigDecimal("0.90")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalScore");
        assertThatThrownBy(() -> completeSnapshot(new BigDecimal("85"), new BigDecimal("1.01"), new BigDecimal("0.90")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completeness");
        assertThatThrownBy(() -> completeSnapshot(new BigDecimal("85"), new BigDecimal("0.80"), new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskConfidence");
        assertThatThrownBy(() -> completeSnapshot(new BigDecimal("85"), null, new BigDecimal("0.90")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("completeness");
    }

    @Test
    void incompleteSnapshotCannotPublishAFormalRiskConclusion() {
        assertThatThrownBy(() -> completeSnapshot(
                new BigDecimal("85"), new BigDecimal("0.79"), new BigDecimal("0.90")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("80%")
                .hasMessageContaining("risk conclusion");
    }

    @Test
    void formalRiskConclusionFieldsMustBeAllPresentOrAllAbsent() {
        assertThatThrownBy(() -> snapshotWithConclusion(
                new BigDecimal("85"), RiskLevel.WARNING, null, new BigDecimal("0.90")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formal risk tuple")
                .hasMessageContaining("all present or all absent");

        assertThatThrownBy(() -> snapshotWithConclusion(
                null, RiskLevel.WARNING, RiskStage.REPRICING, new BigDecimal("0.90")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formal risk tuple");

        assertThat(snapshotWithConclusion(null, null, null, null).level()).isNull();
    }

    @Test
    void validatesTimeCorrectionFactorInsteadOfTreatingMAsAZeroToHundredScore() {
        assertThatThrownBy(() -> snapshotWithM(new BigDecimal("0.89")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mScore")
                .hasMessageContaining("0.90")
                .hasMessageContaining("1.20");
        assertThatThrownBy(() -> snapshotWithM(new BigDecimal("1.21")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mScore");

        assertThat(snapshotWithM(new BigDecimal("0.90")).mScore()).isEqualByComparingTo("0.90");
        assertThat(snapshotWithM(new BigDecimal("1.20")).mScore()).isEqualByComparingTo("1.20");
    }

    @Test
    void gateDecisionIsAlwaysShadowModeAndUsesZeroToOneConfidenceScale() {
        GateDecision decision = new GateDecision(
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                SignalDirection.BULLISH,
                new BigDecimal("0.82"),
                new BigDecimal("0.62"),
                RiskGateStatus.DOWNGRADE,
                false,
                "板块风险处于 critical，建议降低置信度",
                "risk-v1",
                AVAILABLE_AT
        );

        assertThat(decision.enforced()).isFalse();
        assertThatThrownBy(() -> new GateDecision(
                decision.object(), decision.horizon(), decision.tradeDate(), decision.signalDirection(),
                decision.originalConfidence(), decision.suggestedConfidence(), decision.suggestedAction(),
                true, decision.reason(), decision.modelVersion(), decision.calculatedAt()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("shadow");

        assertThatThrownBy(() -> new GateDecision(
                decision.object(), decision.horizon(), decision.tradeDate(), decision.signalDirection(),
                null, decision.suggestedConfidence(), decision.suggestedAction(),
                false, decision.reason(), decision.modelVersion(), decision.calculatedAt()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("originalConfidence");
        assertThatThrownBy(() -> new GateDecision(
                decision.object(), decision.horizon(), decision.tradeDate(), decision.signalDirection(),
                decision.originalConfidence(), null, decision.suggestedAction(),
                false, decision.reason(), decision.modelVersion(), decision.calculatedAt()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("suggestedConfidence");
    }

    @Test
    void rejectsEvidenceThatCouldIntroduceFutureInformation() {
        assertThatThrownBy(() -> new RiskEvidence(
                RiskDimension.ATTENTION,
                "announcement_count",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                AVAILABLE_AT,
                OBSERVED_AT,
                "provider",
                RiskDataQualityStatus.AVAILABLE,
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("availableAt");
    }

    @Test
    void evidenceValueSemanticsFollowQualityStatusWithoutInventingZeros() {
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.UNAVAILABLE, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("null");
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.UNAVAILABLE, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("null");
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.INSUFFICIENT_HISTORY, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient_history")
                .hasMessageContaining("null");
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.INSUFFICIENT_HISTORY, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient_history")
                .hasMessageContaining("null");

        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.VALID_ZERO, BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_zero");
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.VALID_ZERO, BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_zero");
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.VALID_ZERO, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_zero");
        assertThat(evidence(RiskDataQualityStatus.VALID_ZERO, BigDecimal.ZERO, BigDecimal.ZERO).score())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.AVAILABLE, null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available");
        assertThatThrownBy(() -> evidence(RiskDataQualityStatus.AVAILABLE, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("available");
        assertThat(evidence(RiskDataQualityStatus.AVAILABLE, BigDecimal.ONE, BigDecimal.ZERO).rawValue())
                .isEqualByComparingTo(BigDecimal.ZERO);

        RiskEvidence staleEvidence = evidence(
                RiskDataQualityStatus.STALE, new BigDecimal("72"), new BigDecimal("12.5")
        );
        assertThat(staleEvidence.score()).isEqualByComparingTo("72");
        assertThat(staleEvidence.rawValue()).isEqualByComparingTo("12.5");
    }

    private RiskSnapshot completeSnapshot(BigDecimal totalScore, BigDecimal completeness, BigDecimal riskConfidence) {
        return new RiskSnapshot(
                new RiskObjectKey(RiskObjectType.MARKET, "CN-A"),
                RiskHorizon.MEDIUM_TERM,
                LocalDate.of(2026, 7, 18),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("85"),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("1.05"), totalScore,
                RiskLevel.WARNING, RiskStage.REPRICING, completeness, riskConfidence,
                List.of(), "risk-v1", AVAILABLE_AT
        );
    }

    private RiskSnapshot snapshotWithM(BigDecimal mScore) {
        return new RiskSnapshot(
                new RiskObjectKey(RiskObjectType.MARKET, "CN-A"),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                null, null, null, null, null, mScore, null,
                null, null, new BigDecimal("0.50"), null,
                List.of(), "risk-v1", AVAILABLE_AT
        );
    }

    private RiskSnapshot snapshotWithConclusion(
            BigDecimal totalScore,
            RiskLevel level,
            RiskStage stage,
            BigDecimal riskConfidence
    ) {
        return new RiskSnapshot(
                new RiskObjectKey(RiskObjectType.MARKET, "CN-A"),
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("85"),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("1.05"), totalScore,
                level, stage, new BigDecimal("0.80"), riskConfidence,
                List.of(), "risk-v1", AVAILABLE_AT
        );
    }

    private RiskEvidence evidence(
            RiskDataQualityStatus status,
            BigDecimal score,
            BigDecimal rawValue
    ) {
        return new RiskEvidence(
                RiskDimension.CONTAGION,
                "northbound_flow",
                score,
                rawValue,
                OBSERVED_AT,
                AVAILABLE_AT,
                "aktools",
                status,
                Map.of()
        );
    }
}
