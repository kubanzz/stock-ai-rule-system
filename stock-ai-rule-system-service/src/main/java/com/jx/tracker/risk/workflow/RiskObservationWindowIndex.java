package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskHorizonProfile;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskObservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** 按对象、周期、指标与交易日预索引观测，避免回填时反复扫描完整五年集合。 */
final class RiskObservationWindowIndex {

    private final Map<ObjectHorizon, Map<IndicatorSeries, NavigableMap<LocalDate, List<RiskObservation>>>> series;
    private int lastVisitedTradingDays;

    RiskObservationWindowIndex(List<RiskObservation> observations) {
        this.series = new HashMap<>();
        if (observations == null) {
            return;
        }
        for (RiskObservation observation : observations) {
            series.computeIfAbsent(
                            new ObjectHorizon(observation.object(), observation.horizon()),
                            ignored -> new HashMap<>())
                    .computeIfAbsent(
                            new IndicatorSeries(observation.dimension().getCode(), observation.indicatorCode(),
                                    observation.componentCode()),
                            ignored -> new TreeMap<>())
                    .computeIfAbsent(observation.tradeDate(), ignored -> new ArrayList<>())
                    .add(observation);
        }
    }

    List<RiskObservation> window(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
        lastVisitedTradingDays = 0;
        int maximum = RiskHorizonProfile.forHorizon(horizon).maxHistoryDays();
        List<RiskObservation> result = new ArrayList<>();
        Map<IndicatorSeries, NavigableMap<LocalDate, List<RiskObservation>>> objectSeries =
                series.getOrDefault(new ObjectHorizon(object, horizon), Map.of());
        for (NavigableMap<LocalDate, List<RiskObservation>> datedSeries : objectSeries.values()) {
            int eligibleTradingDays = 0;
            for (Map.Entry<LocalDate, List<RiskObservation>> entry
                    : datedSeries.headMap(tradeDate, true).descendingMap().entrySet()) {
                List<RiskObservation> eligibleRevisions = entry.getValue().stream()
                        .filter(observation -> !observation.availableAt().isAfter(asOf))
                        .sorted(Comparator.comparing(RiskObservation::availableAt))
                        .toList();
                if (eligibleRevisions.isEmpty()) {
                    continue;
                }
                result.addAll(eligibleRevisions);
                eligibleTradingDays++;
                lastVisitedTradingDays++;
                if (eligibleTradingDays == maximum) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    int lastVisitedTradingDays() {
        return lastVisitedTradingDays;
    }

    private record ObjectHorizon(RiskObjectKey object, RiskHorizon horizon) {
    }

    private record IndicatorSeries(String dimensionCode, String indicatorCode, String componentCode) {
    }
}
