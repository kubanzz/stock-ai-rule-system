package com.jx.tracker.risk.data.market;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public final class MarketRiskCalculations {

    private static final int SCALE = 10;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private MarketRiskCalculations() {
    }

    public static Optional<BigDecimal> rollingReturn(List<BigDecimal> closes, int window) {
        requireWindow(window);
        if (closes == null || closes.size() <= window) {
            return Optional.empty();
        }
        int lastIndex = closes.size() - 1;
        BigDecimal first = closes.get(lastIndex - window);
        BigDecimal last = closes.get(lastIndex);
        if (first == null || last == null) {
            return Optional.empty();
        }
        if (first.signum() == 0) {
            throw new IllegalArgumentException("rolling return denominator must not be zero");
        }
        return Optional.of(last.divide(first, SCALE, ROUNDING).subtract(BigDecimal.ONE));
    }

    public static Optional<BigDecimal> relativeReturn(
            List<BigDecimal> closes,
            List<BigDecimal> benchmarkCloses,
            int window
    ) {
        Optional<BigDecimal> asset = rollingReturn(closes, window);
        Optional<BigDecimal> benchmark = rollingReturn(benchmarkCloses, window);
        if (asset.isEmpty() || benchmark.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(asset.orElseThrow().subtract(benchmark.orElseThrow()).setScale(SCALE, ROUNDING));
    }

    public static Optional<BigDecimal> volumeRatio(List<BigDecimal> volumes, int shortWindow, int longWindow) {
        requireWindow(shortWindow);
        if (longWindow < shortWindow) {
            throw new IllegalArgumentException("longWindow must not be shorter than shortWindow");
        }
        if (volumes == null || volumes.size() < longWindow) {
            return Optional.empty();
        }
        BigDecimal shortAverage = trailingAverage(volumes, shortWindow);
        BigDecimal longAverage = trailingAverage(volumes, longWindow);
        if (longAverage.signum() == 0) {
            throw new IllegalArgumentException("volume ratio denominator must not be zero");
        }
        return Optional.of(shortAverage.divide(longAverage, SCALE, ROUNDING));
    }

    public static Optional<BigDecimal> standardizedLatestReturn(List<BigDecimal> closes, int sampleWindow) {
        requireWindow(sampleWindow);
        if (closes == null || closes.size() <= sampleWindow) {
            return Optional.empty();
        }
        int firstIndex = closes.size() - sampleWindow - 1;
        java.util.ArrayList<BigDecimal> returns = new java.util.ArrayList<>(sampleWindow);
        for (int index = firstIndex + 1; index < closes.size(); index++) {
            BigDecimal previous = closes.get(index - 1);
            BigDecimal current = closes.get(index);
            if (previous == null || current == null) {
                return Optional.empty();
            }
            if (previous.signum() == 0) {
                throw new IllegalArgumentException("standardized return denominator must not be zero");
            }
            returns.add(current.divide(previous, SCALE + 4, ROUNDING).subtract(BigDecimal.ONE));
        }
        return standardizedLatestValue(returns, sampleWindow);
    }

    public static Optional<BigDecimal> standardizedLatestValue(List<BigDecimal> values, int sampleWindow) {
        requireWindow(sampleWindow);
        if (values == null || values.size() < sampleWindow) {
            return Optional.empty();
        }
        List<BigDecimal> sample = values.subList(values.size() - sampleWindow, values.size());
        if (sample.stream().anyMatch(java.util.Objects::isNull)) {
            return Optional.empty();
        }
        BigDecimal mean = sample.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(sampleWindow), SCALE + 6, ROUNDING);
        BigDecimal variance = sample.stream()
                .map(value -> value.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(sampleWindow), SCALE + 6, ROUNDING);
        if (variance.signum() == 0) {
            return Optional.empty();
        }
        BigDecimal standardDeviation = variance.sqrt(MathContext.DECIMAL64);
        return Optional.of(sample.getLast().subtract(mean).divide(standardDeviation, SCALE, ROUNDING));
    }

    public static MarketBreadth marketBreadth(
            int advancingCount,
            int decliningCount,
            int newHighCount,
            int newLowCount,
            int aboveMovingAverageCount,
            int totalCount
    ) {
        if (totalCount <= 0 || advancingCount < 0 || decliningCount < 0
                || newHighCount < 0 || newLowCount < 0 || aboveMovingAverageCount < 0
                || advancingCount + decliningCount > totalCount
                || newHighCount > totalCount || newLowCount > totalCount || aboveMovingAverageCount > totalCount) {
            throw new IllegalArgumentException("invalid market breadth counts");
        }
        BigDecimal total = BigDecimal.valueOf(totalCount);
        return new MarketBreadth(
                BigDecimal.valueOf(advancingCount).divide(total, SCALE, ROUNDING),
                BigDecimal.valueOf(newHighCount - newLowCount).divide(total, SCALE, ROUNDING),
                BigDecimal.valueOf(aboveMovingAverageCount).divide(total, SCALE, ROUNDING)
        );
    }

    public static Optional<BigDecimal> distanceFromMovingAverage(List<BigDecimal> closes, int window) {
        requireWindow(window);
        if (closes == null || closes.size() < window) {
            return Optional.empty();
        }
        BigDecimal average = trailingAverage(closes, window);
        if (average.signum() == 0) {
            throw new IllegalArgumentException("moving average must not be zero");
        }
        return Optional.of(closes.getLast().divide(average, SCALE, ROUNDING).subtract(BigDecimal.ONE));
    }

    private static BigDecimal trailingAverage(List<BigDecimal> values, int window) {
        List<BigDecimal> sample = values.subList(values.size() - window, values.size());
        if (sample.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("calculation input must not contain null");
        }
        return sample.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), SCALE + 2, ROUNDING);
    }

    private static void requireWindow(int window) {
        if (window <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }
    }
}
