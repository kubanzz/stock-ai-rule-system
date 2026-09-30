package com.jx.tracker.signal;

import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.signal.service.SignalScore;
import com.jx.tracker.signal.service.SignalScoringService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SignalScoringServiceTest {

    private final SignalScoringService scoringService = new SignalScoringService();

    @Test
    void emitsHighRiskWhilePreservingBullishDirectionWhenRiskScoreIsHigh() {
        SignalScore score = scoringService.score(new BigDecimal("82"), new BigDecimal("15"), new BigDecimal("85"));

        assertThat(score.signal()).isEqualTo(SignalType.HIGH_RISK.getCode());
        assertThat(score.signalDirection()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("高风险");
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("0.8500"));
    }

    @Test
    void preservesBullishDirectionWhenRiskScoreIsElevatedButBelowHighRiskThreshold() {
        SignalScore score = scoringService.score(new BigDecimal("72"), BigDecimal.ZERO, new BigDecimal("72"));

        assertThat(score.signal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalDirection()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("强看涨");
    }

    @Test
    void downgradesToWatchWhenBullishAndBearishRulesConflict() {
        SignalScore score = scoringService.score(new BigDecimal("72"), new BigDecimal("65"), new BigDecimal("30"));

        assertThat(score.signal()).isEqualTo(SignalType.WATCH.getCode());
        assertThat(score.signalDirection()).isEqualTo(SignalType.WATCH.getCode());
        assertThat(score.signalLevel()).isEqualTo("观望");
    }

    @Test
    void emitsBullishOnlyWhenBullishScoreIsStrongAndRiskIsControlled() {
        SignalScore score = scoringService.score(new BigDecimal("76"), new BigDecimal("20"), new BigDecimal("45"));

        assertThat(score.signal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalDirection()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("强看涨");
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("0.7600"));
    }

    @Test
    void emitsBearishWhenBearishScoreDominatesWithoutHighRisk() {
        SignalScore score = scoringService.score(new BigDecimal("20"), new BigDecimal("64"), new BigDecimal("35"));

        assertThat(score.signal()).isEqualTo(SignalType.BEARISH.getCode());
        assertThat(score.signalDirection()).isEqualTo(SignalType.BEARISH.getCode());
        assertThat(score.signalLevel()).isEqualTo("偏看跌");
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("0.6400"));
    }

    @Test
    void clampsOutOfRangeScoresBeforeClassifyingSignal() {
        SignalScore score = scoringService.score(new BigDecimal("150"), new BigDecimal("-5"), new BigDecimal("-10"));

        assertThat(score.signal()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.signalDirection()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(score.confidence()).isEqualByComparingTo(new BigDecimal("1.0000"));
    }

    @Test
    void appliesStrategyThresholdsAndRiskOverride() {
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        strategy.setBullishThreshold(new BigDecimal("65"));
        strategy.setBearishThreshold(new BigDecimal("75"));
        strategy.setRiskThreshold(new BigDecimal("50"));

        assertThat(scoringService.score(new BigDecimal("60"), BigDecimal.ZERO,
                BigDecimal.ZERO, strategy).signal()).isEqualTo(SignalType.WATCH.getCode());
        SignalScore risk = scoringService.score(new BigDecimal("70"), BigDecimal.ZERO,
                new BigDecimal("55"), strategy);
        assertThat(risk.signal()).isEqualTo(SignalType.HIGH_RISK.getCode());
        assertThat(risk.signalDirection()).isEqualTo(SignalType.BULLISH.getCode());
        assertThat(scoringService.explain(new BigDecimal("70"), BigDecimal.ZERO,
                new BigDecimal("55"), risk, java.util.List.of(), strategy)
                .thresholds().highRisk()).isEqualByComparingTo("50");
    }
}
