package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskEvent;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** 按对象与事件事实日期索引窗口，避免每个评分日期重复扫描五年事件集合。 */
final class RiskEventWindowIndex {

    private final Map<RiskObjectKey, NavigableMap<LocalDate, List<RiskEvent>>> byObjectAndDate;
    private int lastVisitedRows;

    RiskEventWindowIndex(List<RiskEvent> events) {
        byObjectAndDate = new HashMap<>();
        if (events == null) {
            return;
        }
        for (RiskEvent event : events) {
            byObjectAndDate.computeIfAbsent(event.object(), ignored -> new TreeMap<>())
                    .computeIfAbsent(event.tradeDate(), ignored -> new ArrayList<>())
                    .add(event);
        }
    }

    List<RiskEvent> window(
            RiskObjectKey object,
            LocalDate startDate,
            LocalDate endDate,
            LocalDateTime asOf
    ) {
        lastVisitedRows = 0;
        NavigableMap<LocalDate, List<RiskEvent>> dated = byObjectAndDate.get(object);
        if (dated == null || startDate == null || startDate.isAfter(endDate)) {
            return List.of();
        }
        List<RiskEvent> result = new ArrayList<>();
        for (List<RiskEvent> revisions : dated.subMap(startDate, true, endDate, true).values()) {
            for (RiskEvent event : revisions) {
                lastVisitedRows++;
                if (!event.availableAt().isAfter(asOf)
                        && (event.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || event.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)) {
                    result.add(event);
                }
            }
        }
        result.sort(Comparator.comparing(RiskEvent::tradeDate)
                .thenComparing(RiskEvent::availableAt)
                .thenComparing(RiskEvent::eventKey));
        return List.copyOf(result);
    }

    int lastVisitedRows() {
        return lastVisitedRows;
    }
}
