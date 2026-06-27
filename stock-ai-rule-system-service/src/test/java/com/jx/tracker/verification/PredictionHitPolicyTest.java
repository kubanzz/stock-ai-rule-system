package com.jx.tracker.verification;

import com.jx.tracker.domain.enums.SignalType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PredictionHitPolicyTest {

    private final PredictionHitPolicy policy = new PredictionHitPolicy();

    @Test
    void bullishSignalHitsWhenForwardReturnIsPositive() {
        assertThat(policy.isHit(SignalType.BULLISH.getCode(), new BigDecimal("0.0120"))).isTrue();
        assertThat(policy.isHit(SignalType.BULLISH.getCode(), new BigDecimal("-0.0001"))).isFalse();
    }

    @Test
    void bearishSignalHitsWhenForwardReturnIsNegative() {
        assertThat(policy.isHit(SignalType.BEARISH.getCode(), new BigDecimal("-0.0100"))).isTrue();
        assertThat(policy.isHit(SignalType.BEARISH.getCode(), new BigDecimal("0.0020"))).isFalse();
    }

    @Test
    void highRiskSignalHitsWhenForwardReturnIsNegativeOrFlat() {
        assertThat(policy.isHit(SignalType.HIGH_RISK.getCode(), new BigDecimal("-0.0001"))).isTrue();
        assertThat(policy.isHit(SignalType.HIGH_RISK.getCode(), BigDecimal.ZERO)).isTrue();
        assertThat(policy.isHit(SignalType.HIGH_RISK.getCode(), new BigDecimal("0.0001"))).isFalse();
    }

    @Test
    void watchSignalIsNotCountedAsAHit() {
        assertThat(policy.isHit(SignalType.WATCH.getCode(), BigDecimal.ZERO)).isFalse();
    }
}
