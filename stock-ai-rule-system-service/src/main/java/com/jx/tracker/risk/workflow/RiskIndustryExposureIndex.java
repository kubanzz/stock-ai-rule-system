package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** 按股票及有效期起点索引行业暴露，避免回填时对全部股票暴露重复线性扫描。 */
final class RiskIndustryExposureIndex {

    private final Map<RiskObjectKey, NavigableMap<LocalDate, List<IndustryExposure>>> byStock;
    private int lastVisitedRows;

    RiskIndustryExposureIndex(List<IndustryExposure> exposures) {
        byStock = new HashMap<>();
        if (exposures == null) {
            return;
        }
        for (IndustryExposure exposure : exposures) {
            byStock.computeIfAbsent(exposure.stock(), ignored -> new TreeMap<>())
                    .computeIfAbsent(exposure.validFrom(), ignored -> new ArrayList<>())
                    .add(exposure);
        }
    }

    Optional<RiskObjectKey> effectiveSector(
            RiskObjectKey stock,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
        lastVisitedRows = 0;
        NavigableMap<LocalDate, List<IndustryExposure>> periods = byStock.get(stock);
        if (periods == null) {
            return Optional.empty();
        }
        for (List<IndustryExposure> revisions
                : periods.headMap(tradeDate, true).descendingMap().values()) {
            List<IndustryExposure> eligible = revisions.stream()
                    .peek(ignored -> lastVisitedRows++)
                    .filter(exposure -> exposure.isEffectiveOn(tradeDate))
                    .filter(exposure -> !exposure.availableAt().isAfter(asOf))
                    .filter(exposure -> exposure.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                            || exposure.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                    .toList();
            if (!eligible.isEmpty()) {
                return eligible.stream()
                        .max(Comparator.comparing(IndustryExposure::availableAt))
                        .map(IndustryExposure::sector);
            }
        }
        return Optional.empty();
    }

    int lastVisitedRows() {
        return lastVisitedRows;
    }
}
