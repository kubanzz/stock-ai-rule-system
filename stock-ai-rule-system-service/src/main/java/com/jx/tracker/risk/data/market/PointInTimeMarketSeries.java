package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

final class PointInTimeMarketSeries<T extends MarketSourceRecord> {

    private static final Comparator<MarketSourceRecord> REVISION_ORDER = Comparator
            .comparing(MarketSourceRecord::availableAt)
            .thenComparing(MarketSourceRecord::observedAt);

    private final List<LocalDate> tradeDates;
    private final Map<LocalDate, List<T>> revisionsByDate;

    PointInTimeMarketSeries(List<T> points) {
        revisionsByDate = points.stream().collect(Collectors.groupingBy(
                MarketSourceRecord::tradeDate,
                Collectors.collectingAndThen(Collectors.toList(), revisions -> revisions.stream()
                        .sorted((first, second) -> REVISION_ORDER.compare(first, second))
                        .toList())
        ));
        tradeDates = revisionsByDate.keySet().stream().sorted().toList();
    }

    int size() {
        return tradeDates.size();
    }

    LocalDate tradeDateAt(int index) {
        return tradeDates.get(index);
    }

    Optional<T> targetAt(int index) {
        LocalDate tradeDate = tradeDateAt(index);
        return latestAsOf(revisionsByDate.getOrDefault(tradeDate, List.of()), tradeDate.atTime(LocalTime.MAX));
    }

    HistoryWindow<T> trailingWindow(int targetIndex, LocalDateTime asOf, int maximumPoints) {
        if (maximumPoints <= 0) {
            throw new IllegalArgumentException("maximumPoints must be positive");
        }
        int firstIndex = Math.max(0, targetIndex - maximumPoints + 1);
        List<LocalDate> expectedDates = List.copyOf(tradeDates.subList(firstIndex, targetIndex + 1));
        Map<LocalDate, T> selected = new LinkedHashMap<>();
        for (int index = firstIndex; index <= targetIndex; index++) {
            LocalDate tradeDate = tradeDates.get(index);
            latestAsOf(revisionsByDate.getOrDefault(tradeDate, List.of()), asOf)
                    .ifPresent(point -> selected.put(tradeDate, point));
        }
        return new HistoryWindow<>(expectedDates, selected);
    }

    private Optional<T> latestAsOf(List<T> revisions, LocalDateTime asOf) {
        int lower = 0;
        int upper = revisions.size();
        while (lower < upper) {
            int middle = (lower + upper) >>> 1;
            if (revisions.get(middle).availableAt().isAfter(asOf)) {
                upper = middle;
            } else {
                lower = middle + 1;
            }
        }
        return lower == 0 ? Optional.empty() : Optional.of(revisions.get(lower - 1));
    }

    record HistoryWindow<T extends MarketSourceRecord>(
            List<LocalDate> expectedDates,
            Map<LocalDate, T> selectedByDate
    ) {
        HistoryWindow {
            expectedDates = List.copyOf(expectedDates);
            selectedByDate = Map.copyOf(selectedByDate);
        }

        Optional<List<T>> availableTrailing(int requiredPoints) {
            if (requiredPoints <= 0) {
                throw new IllegalArgumentException("requiredPoints must be positive");
            }
            if (expectedDates.size() < requiredPoints) {
                return Optional.empty();
            }
            int firstIndex = expectedDates.size() - requiredPoints;
            List<T> result = new ArrayList<>(requiredPoints);
            for (int index = firstIndex; index < expectedDates.size(); index++) {
                T dependency = selectedByDate.get(expectedDates.get(index));
                if (dependency == null || dependency.qualityStatus() != RiskDataQualityStatus.AVAILABLE) {
                    return Optional.empty();
                }
                result.add(dependency);
            }
            return Optional.of(List.copyOf(result));
        }
    }
}
