package com.jx.tracker.signal;

import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.signal.service.SignalScore;
import com.jx.tracker.signal.service.SignalScoringService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SignalScoringServiceTest {

    private final SignalScoringService scoringService = new SignalScoringService();

    @Test
    void keepsBullishDirectionWhenLegacyRiskScoreIsHigh() {
        SignalScore score = scoringService.score(new BigDecimal("82"), new BigDecimal("15"), new BigDecimal("85"));

        assertThat(score.signal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("强看涨");
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("0.8200"));
    }

    @Test
    void legacyRiskScoreNeverOverridesBullishDirection() {
        SignalScore score = scoringService.score(new BigDecimal("72"), BigDecimal.ZERO, new BigDecimal("72"));

        assertThat(score.signal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("强看涨");
    }

    @Test
    void downgradesToWatchWhenBullishAndBearishRulesConflict() {
        SignalScore score = scoringService.score(new BigDecimal("72"), new BigDecimal("65"), new BigDecimal("30"));

        assertThat(score.signal()).isEqualTo(SignalType.WATCH.getCode());
        assertThat(score.signalLevel()).isEqualTo("观望");
    }

    @Test
    void emitsBullishOnlyWhenBullishScoreIsStrongAndRiskIsControlled() {
        SignalScore score = scoringService.score(new BigDecimal("76"), new BigDecimal("20"), new BigDecimal("45"));

        assertThat(score.signal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("强看涨");
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("0.7600"));
    }

    @Test
    void emitsBearishWhenBearishScoreDominatesWithoutHighRisk() {
        SignalScore score = scoringService.score(new BigDecimal("20"), new BigDecimal("64"), new BigDecimal("35"));

        assertThat(score.signal()).isEqualTo(SignalType.BEARISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("偏看跌");
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("0.6400"));
    }
}
