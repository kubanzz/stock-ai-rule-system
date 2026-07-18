package com.jx.tracker.risk.data.event;

import com.jx.tracker.risk.data.flow.FlowEventSourceRecord;

import java.util.Map;
import java.util.Optional;

/** 可审计事件字典：优先采用源记录的规范经济含义，其次匹配受控分类码。 */
public final class EventEconomicMeaningDictionary {

    private final Map<String, EconomicMeaning> categoryMappings;
    private final Map<EconomicMeaning, String> indicatorMappings;

    public EventEconomicMeaningDictionary(
            Map<String, EconomicMeaning> categoryMappings,
            Map<EconomicMeaning, String> indicatorMappings
    ) {
        this.categoryMappings = Map.copyOf(categoryMappings);
        this.indicatorMappings = Map.copyOf(indicatorMappings);
    }

    public Optional<EconomicMeaning> resolve(FlowEventSourceRecord record) {
        Object explicitMeaning = record.attributes().get("economicMeaning");
        Optional<EconomicMeaning> explicit = EconomicMeaning.tryFromCode(
                explicitMeaning == null ? null : explicitMeaning.toString());
        if (explicit.isPresent()) {
            return explicit;
        }
        Object category = record.attributes().get("announcementCategory");
        return Optional.ofNullable(category == null ? null : categoryMappings.get(category.toString()));
    }

    public Optional<String> indicatorCode(EconomicMeaning meaning) {
        return Optional.ofNullable(indicatorMappings.get(meaning));
    }

    public static EventEconomicMeaningDictionary defaultDictionary() {
        return new EventEconomicMeaningDictionary(
                Map.of(
                        "earnings_warning", EconomicMeaning.CASH_FLOW,
                        "discount_rate_change", EconomicMeaning.DISCOUNT_RATE,
                        "financing_restriction", EconomicMeaning.FINANCING_CONDITIONS,
                        "regulatory_investigation", EconomicMeaning.MARKET_TRUST
                ),
                Map.of(
                        EconomicMeaning.CASH_FLOW, "T1",
                        EconomicMeaning.DISCOUNT_RATE, "T2",
                        EconomicMeaning.FINANCING_CONDITIONS, "T3",
                        EconomicMeaning.MARKET_TRUST, "T4"
                ));
    }
}
