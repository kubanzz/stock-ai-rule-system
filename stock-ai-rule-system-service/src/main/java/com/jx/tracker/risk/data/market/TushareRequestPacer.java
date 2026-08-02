package com.jx.tracker.risk.data.market;

import java.time.Duration;

@FunctionalInterface
interface TushareRequestPacer {

    void awaitPermit();

    static TushareRequestPacer fixedDelay(Duration delay) {
        if (delay == null || delay.isNegative() || delay.isZero()) {
            throw new IllegalArgumentException("TuShare request delay must be positive");
        }
        return () -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted while pacing TuShare requests", exception);
            }
        };
    }
}
