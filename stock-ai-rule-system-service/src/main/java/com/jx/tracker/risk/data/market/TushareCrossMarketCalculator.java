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
import java.util.NavigableMap;
import java.util.TreeMap;

/** Pure point-in-time alignment and rolling correlation for global markets. */
final class TushareCrossMarketCalculator {

    private static final List<String> MARKETS =
            List.of("SPX", "IXIC", "HSI", "N225");
    private static final List<String> PRIOR_SESSION_MARKETS =
            List.of("SPX", "IXIC");
    private static final int CORRELATION_WINDOW = 60;
    private static final int SCALE = 10;

    Calculation calculate(
            List<LocalDate> cnOpenDates,
            Map<LocalDate, BigDecimal> benchmarkCloses,
            Map<String, Map<LocalDate, BigDecimal>> globalCloses,
            LocalDate resultStartDate
    ) {
        if (cnOpenDates == null || benchmarkCloses == null
                || globalCloses == null || resultStartDate == null) {
            throw new IllegalArgumentException(
                    "cross-market series and resultStartDate are required");
        }
        List<LocalDate> sessions = cnOpenDates.stream()
                .distinct()
                .sorted()
                .toList();
        Map<String, NavigableMap<LocalDate, BigDecimal>> series =
                normalizedSeries(globalCloses);
        Deque<ReturnPair> trailingPairs = new ArrayDeque<>();
        AlignedClose previous = null;
        List<CrossMarketMetrics> points = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (LocalDate session : sessions) {
            BigDecimal benchmarkClose = benchmarkCloses.get(session);
            Map<String, Map.Entry<LocalDate, BigDecimal>> selected =
                    selectCompletedCloses(session, series);
            List<String> missing = MARKETS.stream()
                    .filter(code -> !selected.containsKey(code))
                    .toList();
            if (benchmarkClose == null || !missing.isEmpty()) {
                if (!session.isBefore(resultStartDate)) {
                    String reason = benchmarkClose == null
                            ? "000985.CSI"
                            : String.join(",", missing);
                    gaps.add(session + " missing completed close for " + reason);
                }
                continue;
            }

            AlignedClose current = new AlignedClose(
                    session,
                    benchmarkClose,
                    selected.entrySet().stream().collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey,
                                    entry -> entry.getValue().getValue())),
                    selected.entrySet().stream().collect(
                            java.util.stream.Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey,
                                    entry -> entry.getValue().getKey())));
            if (previous == null) {
                previous = current;
                if (!session.isBefore(resultStartDate)) {
                    gaps.add(session + " has fewer than 60 aligned return pairs");
                }
                continue;
            }

            BigDecimal benchmarkReturn = dailyReturn(
                    current.benchmarkClose(), previous.benchmarkClose());
            Map<String, BigDecimal> marketReturns = new LinkedHashMap<>();
            for (String code : MARKETS) {
                marketReturns.put(
                        code,
                        dailyReturn(
                                current.marketCloses().get(code),
                                previous.marketCloses().get(code)));
            }
            BigDecimal basketReturn = marketReturns.values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(MARKETS.size()), SCALE, RoundingMode.HALF_UP);
            trailingPairs.addLast(new ReturnPair(basketReturn, benchmarkReturn));
            if (trailingPairs.size() > CORRELATION_WINDOW) {
                trailingPairs.removeFirst();
            }
            previous = current;

            if (session.isBefore(resultStartDate)) {
                continue;
            }
            if (trailingPairs.size() < CORRELATION_WINDOW) {
                gaps.add(session + " has fewer than 60 aligned return pairs");
                continue;
            }
            BigDecimal correlation = pearson(trailingPairs);
            if (correlation == null) {
                gaps.add(session
                        + " has zero variance in the 60 aligned return pairs");
                continue;
            }
            int confirmedDown = Math.toIntExact(marketReturns.values().stream()
                    .filter(value -> value.signum() < 0)
                    .count());
            points.add(new CrossMarketMetrics(
                    session,
                    basketReturn,
                    correlation,
                    confirmedDown,
                    MARKETS.size(),
                    current.selectedCloseDates()));
        }
        return new Calculation(points, gaps);
    }

    private Map<String, NavigableMap<LocalDate, BigDecimal>> normalizedSeries(
            Map<String, Map<LocalDate, BigDecimal>> globalCloses
    ) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> normalized =
                new LinkedHashMap<>();
        for (String code : MARKETS) {
            NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>();
            Map<LocalDate, BigDecimal> input =
                    globalCloses.getOrDefault(code, Map.of());
            input.forEach((date, close) -> {
                if (date == null || close == null || close.signum() <= 0) {
                    throw new IllegalArgumentException(
                            "invalid cross-market close for " + code);
                }
                closes.put(date, close);
            });
            normalized.put(code, closes);
        }
        return Map.copyOf(normalized);
    }

    private Map<String, Map.Entry<LocalDate, BigDecimal>> selectCompletedCloses(
            LocalDate cnSession,
            Map<String, NavigableMap<LocalDate, BigDecimal>> series
    ) {
        Map<String, Map.Entry<LocalDate, BigDecimal>> selected =
                new LinkedHashMap<>();
        for (String code : MARKETS) {
            Map.Entry<LocalDate, BigDecimal> close =
                    PRIOR_SESSION_MARKETS.contains(code)
                            ? series.get(code).lowerEntry(cnSession)
                            : series.get(code).floorEntry(cnSession);
            if (close != null) {
                selected.put(code, Map.entry(close.getKey(), close.getValue()));
            }
        }
        return Map.copyOf(selected);
    }

    private BigDecimal dailyReturn(BigDecimal current, BigDecimal previous) {
        if (previous.signum() <= 0) {
            throw new IllegalArgumentException(
                    "cross-market previous close must be positive");
        }
        return current.divide(previous, SCALE + 4, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal pearson(Deque<ReturnPair> pairs) {
        double leadingMean = pairs.stream()
                .mapToDouble(pair -> pair.leadingReturn().doubleValue())
                .average()
                .orElseThrow();
        double benchmarkMean = pairs.stream()
                .mapToDouble(pair -> pair.benchmarkReturn().doubleValue())
                .average()
                .orElseThrow();
        double covariance = 0;
        double leadingVariance = 0;
        double benchmarkVariance = 0;
        for (ReturnPair pair : pairs) {
            double leadingDelta =
                    pair.leadingReturn().doubleValue() - leadingMean;
            double benchmarkDelta =
                    pair.benchmarkReturn().doubleValue() - benchmarkMean;
            covariance += leadingDelta * benchmarkDelta;
            leadingVariance += leadingDelta * leadingDelta;
            benchmarkVariance += benchmarkDelta * benchmarkDelta;
        }
        if (leadingVariance == 0 || benchmarkVariance == 0) {
            return null;
        }
        double value = covariance
                / Math.sqrt(leadingVariance * benchmarkVariance);
        double bounded = Math.max(-1.0, Math.min(1.0, value));
        return BigDecimal.valueOf(bounded)
                .setScale(SCALE, RoundingMode.HALF_UP);
    }

    record CrossMarketMetrics(
            LocalDate tradeDate,
            BigDecimal leadingAssetReturn,
            BigDecimal dynamicCorrelation,
            int confirmedDownMarketCount,
            int observedMarketCount,
            Map<String, LocalDate> selectedCloseDates
    ) {
        CrossMarketMetrics {
            selectedCloseDates = Map.copyOf(selectedCloseDates);
        }
    }

    record Calculation(
            List<CrossMarketMetrics> points,
            List<String> gaps
    ) {
        Calculation {
            points = points == null ? List.of() : List.copyOf(points);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }
    }

    private record AlignedClose(
            LocalDate tradeDate,
            BigDecimal benchmarkClose,
            Map<String, BigDecimal> marketCloses,
            Map<String, LocalDate> selectedCloseDates
    ) {
    }

    private record ReturnPair(
            BigDecimal leadingReturn,
            BigDecimal benchmarkReturn
    ) {
    }
}
