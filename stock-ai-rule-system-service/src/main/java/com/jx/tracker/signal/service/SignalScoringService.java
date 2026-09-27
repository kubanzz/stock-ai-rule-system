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
    private static final BigDecimal HIGH_RISK = new BigDecimal("80");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    public SignalScore score(BigDecimal bullishScore, BigDecimal bearishScore, BigDecimal riskScore) {
        BigDecimal bullish = safeScore(bullishScore);
        BigDecimal bearish = safeScore(bearishScore);
        BigDecimal risk = safeScore(riskScore);
        String direction;
        String directionLevel;
        if (bullish.compareTo(BULLISH) >= 0 && bearish.compareTo(BEARISH) >= 0) {
            direction = SignalType.WATCH.getCode();
            directionLevel = "观望";
        } else if (bullish.compareTo(STRONG_BULLISH) >= 0) {
            direction = SignalType.BULLISH.getCode();
            directionLevel = "强看涨";
        } else if (bullish.compareTo(BULLISH) >= 0) {
            direction = SignalType.BULLISH.getCode();
            directionLevel = "偏看涨";
        } else if (bearish.compareTo(BEARISH) >= 0) {
            direction = SignalType.BEARISH.getCode();
            directionLevel = "偏看跌";
        } else {
            direction = SignalType.WATCH.getCode();
            directionLevel = "观望";
        }
        if (risk.compareTo(HIGH_RISK) >= 0) {
            return new SignalScore(SignalType.HIGH_RISK.getCode(), direction, "高风险", confidence(risk));
        }
        return new SignalScore(direction, direction, directionLevel, confidence(bullish.max(bearish)));
    }

    private BigDecimal safeScore(BigDecimal score) {
        return score == null ? BigDecimal.ZERO : score.max(BigDecimal.ZERO).min(ONE_HUNDRED);
    }

    private BigDecimal confidence(BigDecimal score) {
        return score.min(ONE_HUNDRED).divide(ONE_HUNDRED, 4, RoundingMode.HALF_UP);
    }
}
