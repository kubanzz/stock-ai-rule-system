package com.jx.tracker.risk.data.event;

import com.jx.tracker.risk.data.flow.FlowEventDataset;
import com.jx.tracker.risk.data.flow.FlowEventMetrics;
import com.jx.tracker.risk.data.flow.FlowEventSourceRecord;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskObservation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FlowEventTranslator {

    private final EventEconomicMeaningDictionary dictionary;

    public FlowEventTranslator(EventEconomicMeaningDictionary dictionary) {
        this.dictionary = dictionary;
    }

    public FlowEventTranslation translate(FlowEventDataset dataset, FlowEventSourceRecord record) {
        return translate(dataset, record, RiskHorizon.SHORT_TERM, "unknown", null);
    }

    public FlowEventTranslation translate(
            FlowEventDataset dataset,
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            String source,
            String fallbackReason
    ) {
        Map<String, Object> attributes = auditedAttributes(record, fallbackReason);
        return switch (dataset) {
            case MARGIN_FINANCING -> margin(record, horizon, source, attributes);
            case ETF_FUND_FLOW -> etfFlow(record, horizon, source, attributes);
            case EARNINGS_FORECAST -> substantiveEvent(
                    record, horizon, source, attributes, EconomicMeaning.CASH_FLOW);
            case STOCK_ANNOUNCEMENT -> dictionary.resolve(record)
                    .map(meaning -> substantiveEvent(record, horizon, source, attributes, meaning))
                    .orElseGet(() -> FlowEventTranslation.rejected("公告经济含义未映射，不生成风险事件"));
            case SHARE_UNLOCK, SHARE_REDUCTION -> modifierEvent(dataset, record, source, attributes);
        };
    }

    private FlowEventTranslation margin(
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            String source,
            Map<String, Object> attributes
    ) {
        BigDecimal balance = nullToZero(record.value());
        BigDecimal previous = decimalAttribute(record.attributes(), "previousBalance", balance);
        BigDecimal reference = decimalAttribute(record.attributes(), "referenceBalance", previous);
        RiskObservation leverage = observation(
                record, horizon, RiskDimension.STRUCTURAL_FRAGILITY, "V5",
                FlowEventMetrics.balanceLevel(balance, reference), "ratio", source, attributes);
        RiskObservation deleveraging = observation(
                record, horizon, RiskDimension.FORCED_SELLING, "A1",
                FlowEventMetrics.deleveragingProxy(balance, previous), "ratio", source, attributes);
        return new FlowEventTranslation(List.of(leverage, deleveraging), List.of(), null);
    }

    private FlowEventTranslation etfFlow(
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            String source,
            Map<String, Object> attributes
    ) {
        BigDecimal referenceAssets = decimalAttribute(
                record.attributes(), "referenceAssets", nullToZero(record.value()).abs().max(BigDecimal.ONE));
        RiskObservation redemption = observation(
                record, horizon, RiskDimension.FORCED_SELLING, "A2",
                FlowEventMetrics.redemptionProxy(nullToZero(record.value()), referenceAssets),
                "ratio", source, attributes);
        return new FlowEventTranslation(List.of(redemption), List.of(), null);
    }

    private FlowEventTranslation substantiveEvent(
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            String source,
            Map<String, Object> attributes,
            EconomicMeaning meaning
    ) {
        Optional<String> indicatorCode = dictionary.indicatorCode(meaning);
        if (indicatorCode.isEmpty()) {
            return FlowEventTranslation.rejected("经济含义未映射到指标，不生成风险事件");
        }
        BigDecimal severity = FlowEventMetrics.normalizeSeverity(nullToZero(record.value()).abs());
        Map<String, Object> eventPayload = new HashMap<>(attributes);
        eventPayload.put("economicMeaning", meaning.code());
        RiskObservation observation = observation(
                record, horizon, RiskDimension.SUBSTANTIVE_TRIGGER, indicatorCode.get(), severity,
                "score", source, eventPayload);
        RiskEvent event = new RiskEvent(
                record.object(), record.tradeDate(), RiskDimension.SUBSTANTIVE_TRIGGER,
                meaning.code(), stableEventKey(record, meaning.code()), severity,
                record.occurredAt(), record.observedAt(), record.availableAt(), source,
                RiskDataQualityStatus.AVAILABLE, eventPayload);
        return new FlowEventTranslation(List.of(observation), List.of(event), null);
    }

    private FlowEventTranslation modifierEvent(
            FlowEventDataset dataset,
            FlowEventSourceRecord record,
            String source,
            Map<String, Object> attributes
    ) {
        boolean actualReduction = booleanAttribute(record.attributes(), "actualReduction");
        boolean confirmed = actualReduction && (booleanAttribute(record.attributes(), "fundFlowConfirmed")
                || booleanAttribute(record.attributes(), "priceConfirmed"));
        Map<String, Object> payload = new HashMap<>(attributes);
        payload.put("modifierCandidate", true);
        payload.put("dimensionScoreEligible", false);
        payload.put("confirmed", confirmed);
        payload.put("scheduled", booleanAttribute(record.attributes(), "scheduled"));
        BigDecimal severity = confirmed
                ? FlowEventMetrics.normalizeSeverity(nullToZero(record.value()).abs())
                : null;
        RiskEvent event = new RiskEvent(
                record.object(), record.tradeDate(), RiskDimension.FORCED_SELLING,
                dataset.code(), stableEventKey(record, dataset.code()), severity,
                record.occurredAt(), record.observedAt(), record.availableAt(), source,
                RiskDataQualityStatus.AVAILABLE, payload);
        return new FlowEventTranslation(List.of(), List.of(event), null);
    }

    private RiskObservation observation(
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            RiskDimension dimension,
            String indicatorCode,
            BigDecimal value,
            String unit,
            String source,
            Map<String, Object> attributes
    ) {
        Map<String, Object> horizonAttributes = new HashMap<>(attributes);
        horizonAttributes.put("windowDays", windowDays(horizon));
        return new RiskObservation(
                record.object(), horizon, record.tradeDate(), dimension, indicatorCode, value, unit,
                record.observedAt(), record.availableAt(), source,
                RiskDataQualityStatus.AVAILABLE, horizonAttributes);
    }

    private Map<String, Object> auditedAttributes(FlowEventSourceRecord record, String fallbackReason) {
        Map<String, Object> attributes = new HashMap<>(record.attributes());
        attributes.put("sourceRecordId", record.recordId());
        attributes.put("sourceCursor", record.cursor());
        if (fallbackReason != null && !fallbackReason.isBlank()) {
            attributes.put("fallbackReason", fallbackReason);
        }
        return attributes;
    }

    private String stableEventKey(FlowEventSourceRecord record, String type) {
        return type + ":" + record.object().objectType().getCode() + ":"
                + record.object().objectId() + ":" + record.recordId();
    }

    private BigDecimal decimalAttribute(Map<String, Object> attributes, String key, BigDecimal defaultValue) {
        Object value = attributes.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    private boolean booleanAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value instanceof Boolean bool ? bool : value != null && Boolean.parseBoolean(value.toString());
    }

    private BigDecimal nullToZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private int windowDays(RiskHorizon horizon) {
        return switch (horizon) {
            case SHORT_TERM -> 5;
            case MEDIUM_TERM -> 20;
            case LONG_TERM -> 60;
        };
    }
}
