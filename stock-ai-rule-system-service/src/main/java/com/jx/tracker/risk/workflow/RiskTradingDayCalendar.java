package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.provider.RiskObservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 由 point-in-time 可用的价格/本地确认观测建立交易日序列。
 * 公告和事件日期不会进入序列；没有交易日证据时不以自然日降级替代。
 */
final class RiskTradingDayCalendar {

    private static final Set<String> MARKET_DAILY_PRICE_INDICATORS = Set.of(
            "V3", "V4", "C1", "C3", "C4", "C5", "A3", "A5");
    private final NavigableMap<LocalDate, List<LocalDateTime>> availabilityByDate = new TreeMap<>();

    RiskTradingDayCalendar(List<RiskObservation> observations) {
        if (observations == null) {
            return;
        }
        for (RiskObservation observation : observations) {
            if (!isTradingDayEvidence(observation)) {
                continue;
            }
            availabilityByDate.computeIfAbsent(observation.tradeDate(), ignored -> new ArrayList<>())
                    .add(observation.availableAt());
        }
    }

    Optional<LocalDate> windowStart(
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
        int maximumTradingDays = switch (horizon) {
            case SHORT_TERM -> 5;
            case MEDIUM_TERM -> 20;
            case LONG_TERM -> 60;
        };
        LocalDate earliest = null;
        int included = 0;
        for (Map.Entry<LocalDate, List<LocalDateTime>> entry
                : availabilityByDate.headMap(tradeDate, true).descendingMap().entrySet()) {
            if (entry.getValue().stream().noneMatch(availableAt -> !availableAt.isAfter(asOf))) {
                continue;
            }
            earliest = entry.getKey();
            included++;
            if (included == maximumTradingDays) {
                break;
            }
        }
        return Optional.ofNullable(earliest);
    }

    private boolean isTradingDayEvidence(RiskObservation observation) {
        if (observation.qualityStatus() != RiskDataQualityStatus.AVAILABLE
                && observation.qualityStatus() != RiskDataQualityStatus.VALID_ZERO) {
            return false;
        }
        return booleanAttribute(observation, "tradingDay")
                || booleanAttribute(observation, "marketPrice")
                || isMarketDailyPriceProvenance(observation);
    }

    private boolean isMarketDailyPriceProvenance(RiskObservation observation) {
        return "market_daily".equals(observation.attributes().get("datasetCode"))
                && MARKET_DAILY_PRICE_INDICATORS.contains(observation.indicatorCode());
    }

    private boolean booleanAttribute(RiskObservation observation, String key) {
        Object value = observation.attributes().get(key);
        return value instanceof Boolean flag ? flag : value != null && Boolean.parseBoolean(value.toString());
    }
}
