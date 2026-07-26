package com.jx.tracker.risk.data.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Pure rolling-window calculation for TuShare A-share daily breadth. */
final class TushareBreadthCalculator {

    private static final int HIGH_LOW_WINDOW = 252;
    private static final int MOVING_AVERAGE_WINDOW = 50;
    private static final BigDecimal FORMAL_COVERAGE = new BigDecimal("0.95");

    Calculation calculate(List<DailyBar> bars, LocalDate resultStartDate) {
        if (bars == null || resultStartDate == null) {
            throw new IllegalArgumentException(
                    "daily bars and resultStartDate are required");
        }
        Map<LocalDate, Map<String, DailyBar>> barsByDate = new TreeMap<>();
        for (DailyBar bar : bars) {
            Map<String, DailyBar> crossSection = barsByDate.computeIfAbsent(
                    bar.tradeDate(), ignored -> new LinkedHashMap<>());
            if (crossSection.put(bar.code(), bar) != null) {
                throw new IllegalArgumentException(
                        "duplicate daily breadth bar for "
                                + bar.code() + " on " + bar.tradeDate());
            }
        }

        Map<String, RollingState> histories = new LinkedHashMap<>();
        List<BreadthCounts> points = new ArrayList<>();
        barsByDate.forEach((tradeDate, crossSection) -> {
            int advancing = 0;
            int declining = 0;
            int newHigh = 0;
            int newLow = 0;
            int aboveMovingAverage = 0;
            int eligible = 0;
            for (DailyBar bar : crossSection.values()) {
                RollingState history = histories.computeIfAbsent(
                        bar.code(), ignored -> new RollingState());
                history.add(bar.close());
                if (!history.eligible()) {
                    continue;
                }
                eligible++;
                int direction = bar.close().compareTo(bar.previousClose());
                if (direction > 0) {
                    advancing++;
                } else if (direction < 0) {
                    declining++;
                }
                if (bar.close().compareTo(history.high()) == 0) {
                    newHigh++;
                }
                if (bar.close().compareTo(history.low()) == 0) {
                    newLow++;
                }
                if (bar.close().compareTo(history.movingAverage()) > 0) {
                    aboveMovingAverage++;
                }
            }
            if (tradeDate.isBefore(resultStartDate) || crossSection.isEmpty()) {
                return;
            }
            int actual = crossSection.size();
            BigDecimal coverage = BigDecimal.valueOf(eligible)
                    .divide(BigDecimal.valueOf(actual), 4, RoundingMode.HALF_UP);
            points.add(new BreadthCounts(
                    tradeDate,
                    advancing,
                    declining,
                    newHigh,
                    newLow,
                    aboveMovingAverage,
                    actual,
                    eligible,
                    coverage,
                    coverage.compareTo(FORMAL_COVERAGE) >= 0));
        });
        return new Calculation(points);
    }

    record DailyBar(
            String code,
            LocalDate tradeDate,
            BigDecimal close,
            BigDecimal previousClose
    ) {
        DailyBar {
            if (code == null || code.isBlank() || tradeDate == null
                    || close == null || previousClose == null
                    || close.signum() <= 0 || previousClose.signum() < 0) {
                throw new IllegalArgumentException("invalid daily breadth bar");
            }
            code = code.trim().toUpperCase();
        }
    }

    record BreadthCounts(
            LocalDate tradeDate,
            int advancingCount,
            int decliningCount,
            int newHighCount,
            int newLowCount,
            int aboveMovingAverageCount,
            int actualCount,
            int eligibleCount,
            BigDecimal coverage,
            boolean formal
    ) {
    }

    record Calculation(List<BreadthCounts> points) {
        Calculation {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    private static final class RollingState {

        private final Deque<BigDecimal> highLowWindow = new ArrayDeque<>();
        private final TreeMap<BigDecimal, Integer> closeCounts = new TreeMap<>();
        private final Deque<BigDecimal> movingAverageWindow = new ArrayDeque<>();
        private BigDecimal movingAverageSum = BigDecimal.ZERO;

        private void add(BigDecimal close) {
            highLowWindow.addLast(close);
            closeCounts.merge(close, 1, Integer::sum);
            if (highLowWindow.size() > HIGH_LOW_WINDOW) {
                BigDecimal removed = highLowWindow.removeFirst();
                closeCounts.computeIfPresent(
                        removed,
                        (ignored, count) -> count == 1 ? null : count - 1);
            }

            movingAverageWindow.addLast(close);
            movingAverageSum = movingAverageSum.add(close);
            if (movingAverageWindow.size() > MOVING_AVERAGE_WINDOW) {
                movingAverageSum =
                        movingAverageSum.subtract(movingAverageWindow.removeFirst());
            }
        }

        private boolean eligible() {
            return highLowWindow.size() == HIGH_LOW_WINDOW;
        }

        private BigDecimal high() {
            return closeCounts.lastKey();
        }

        private BigDecimal low() {
            return closeCounts.firstKey();
        }

        private BigDecimal movingAverage() {
            return movingAverageSum.divide(
                    BigDecimal.valueOf(MOVING_AVERAGE_WINDOW),
                    10,
                    RoundingMode.HALF_UP);
        }
    }
}
