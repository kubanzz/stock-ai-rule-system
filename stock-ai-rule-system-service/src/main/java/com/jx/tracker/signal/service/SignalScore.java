package com.jx.tracker.signal.service;

import java.math.BigDecimal;

public record SignalScore(
        String signal,
        String signalDirection,
        String signalLevel,
        BigDecimal confidence
) {

    public SignalScore(String signal, String signalLevel, BigDecimal confidence) {
        this(signal, signal, signalLevel, confidence);
    }
}
