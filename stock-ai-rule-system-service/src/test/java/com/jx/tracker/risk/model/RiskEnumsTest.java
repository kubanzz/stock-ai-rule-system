package com.jx.tracker.risk.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskEnumsTest {

    @Test
    void exposesStableWireCodes() {
        assertThat(SignalDirection.codes()).containsExactly("bullish", "bearish", "watch");
        assertThat(RiskLevel.codes()).containsExactly("normal", "watch", "warning", "critical");
        assertThat(RiskGateStatus.codes()).containsExactly("normal", "notice", "downgrade", "block");
        assertThat(RiskObjectType.codes()).containsExactly("market", "sector", "stock");
        assertThat(RiskHorizon.codes()).containsExactly("1-5d", "5-20d", "20-60d");
        assertThat(RiskStage.codes()).containsExactly("fragile", "repricing", "stampede", "easing");
        assertThat(RiskDimension.codes()).containsExactly("V", "T", "S", "C", "A");
        assertThat(RiskDataQualityStatus.codes()).containsExactly(
                "available", "valid_zero", "unavailable", "stale", "insufficient_history"
        );
    }

    @Test
    void strictlyParsesWireCodesAndRejectsUnknownOrNullValues() {
        assertThat(RiskHorizon.fromCode("1-5d")).isEqualTo(RiskHorizon.SHORT_TERM);
        assertThat(RiskDimension.fromCode("V")).isEqualTo(RiskDimension.STRUCTURAL_FRAGILITY);
        assertThat(RiskDataQualityStatus.fromCode("valid_zero")).isEqualTo(RiskDataQualityStatus.VALID_ZERO);

        assertThatThrownBy(() -> RiskLevel.fromCode("WARNING"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WARNING");
        assertThatThrownBy(() -> SignalDirection.fromCode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
