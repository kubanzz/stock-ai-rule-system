package com.jx.tracker.signal.service;

import com.jx.tracker.domain.enums.SignalType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class SignalScoringService {

    private static final BigDecimal STRONG_BULLISH = new BigDecimal("70");
    private static final BigDecimal BULLISH = new BigDecimal("55");
    private static final BigDecimal BEARISH = new BigDecimal("60");
    private static final BigDecimal RISK_CONFLICT = new BigDecimal("70");
    private static final BigDecimal HIGH_RISK = new BigDecimal("80");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public SignalScore score(BigDecimal bullishScore, BigDecimal bearishScore, BigDecimal riskScore) {
        BigDecimal bullish = safeScore(bullishScore);
        BigDecimal bearish = safeScore(bearishScore);
        BigDecimal risk = safeScore(riskScore);

        if (risk.compareTo(HIGH_RISK) >= 0) {
            return new SignalScore(SignalType.HIGH_RISK.getCode(), "高风险", confidence(risk));
        }
        if (risk.compareTo(RISK_CONFLICT) >= 0 && bullish.compareTo(BULLISH) >= 0) {
            return new SignalScore(SignalType.WATCH.getCode(), "观望", confidence(risk.max(bullish)));
        }
        if (bullish.compareTo(BULLISH) >= 0 && bearish.compareTo(BEARISH) >= 0) {
            return new SignalScore(SignalType.WATCH.getCode(), "观望", confidence(bullish.max(bearish)));
        }
        if (bullish.compareTo(STRONG_BULLISH) >= 0 && risk.compareTo(new BigDecimal("50")) < 0) {
            return new SignalScore(SignalType.BULLISH.getCode(), "强看涨", confidence(bullish));
        }
        if (bullish.compareTo(BULLISH) >= 0 && risk.compareTo(RISK_CONFLICT) < 0) {
            return new SignalScore(SignalType.BULLISH.getCode(), "偏看涨", confidence(bullish));
        }
        if (bearish.compareTo(BEARISH) >= 0) {
            return new SignalScore(SignalType.BEARISH.getCode(), "偏看跌", confidence(bearish));
        }
        return new SignalScore(SignalType.WATCH.getCode(), "观望", confidence(bullish.max(bearish).max(risk)));
    }

    private BigDecimal safeScore(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score.max(BigDecimal.ZERO);
    }

    private BigDecimal confidence(BigDecimal score) {
        return score.min(ONE_HUNDRED).divide(ONE_HUNDRED, 4, RoundingMode.HALF_UP);
    }
}
