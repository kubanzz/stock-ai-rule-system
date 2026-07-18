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
                new BigDecimal("80.00"),
                null,
                null,
                null,
                new BigDecimal("0.58"),
                new BigDecimal("0.64"),
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
                        "\"riskConfidence\":0.64",
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

    private RiskSnapshot completeSnapshot(BigDecimal totalScore, BigDecimal completeness, BigDecimal riskConfidence) {
        return new RiskSnapshot(
                new RiskObjectKey(RiskObjectType.MARKET, "CN-A"),
                RiskHorizon.MEDIUM_TERM,
                LocalDate.of(2026, 7, 18),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("85"),
                new BigDecimal("85"), new BigDecimal("85"), new BigDecimal("85"), totalScore,
                RiskLevel.WARNING, RiskStage.REPRICING, completeness, riskConfidence,
                List.of(), "risk-v1", AVAILABLE_AT
        );
    }
}
