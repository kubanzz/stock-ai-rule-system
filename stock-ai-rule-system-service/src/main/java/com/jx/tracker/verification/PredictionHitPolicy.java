package com.jx.tracker.verification;

import com.jx.tracker.domain.enums.SignalType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PredictionHitPolicy {

    public boolean isHit(String signal, BigDecimal forwardReturn) {
        if (signal == null || forwardReturn == null) {
            return false;
        }
        SignalType signalType = SignalType.fromCode(signal);
        int compareToZero = forwardReturn.compareTo(BigDecimal.ZERO);
        return switch (signalType) {
            case BULLISH -> compareToZero > 0;
            case BEARISH -> compareToZero < 0;
            case HIGH_RISK -> compareToZero <= 0;
            case WATCH -> false;
        };
    }
}
