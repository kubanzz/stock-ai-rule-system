package com.jx.tracker.signal.service;

import java.math.BigDecimal;

public record SignalScore(
        String signal,
        String signalLevel,
        BigDecimal confidence
) {
}
