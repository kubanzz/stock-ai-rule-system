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
                    dataset, record, horizon, source, attributes, EconomicMeaning.CASH_FLOW);
            case STOCK_ANNOUNCEMENT -> dictionary.resolve(record)
                    .map(meaning -> substantiveEvent(dataset, record, horizon, source, attributes, meaning))
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
        BigDecimal balance = record.value();
        BigDecimal previous = decimalAttribute(record.attributes(), "previousBalance", null);
        BigDecimal reference = decimalAttribute(record.attributes(), "referenceBalance", null);
        RiskObservation leverage = ratioObservation(
                record, horizon, RiskDimension.STRUCTURAL_FRAGILITY, "V5", source, attributes,
                () -> FlowEventMetrics.balanceLevel(balance, reference));
        RiskObservation deleveraging = ratioObservation(
                record, horizon, RiskDimension.FORCED_SELLING, "A1", source, attributes,
                () -> FlowEventMetrics.deleveragingProxy(balance, previous));
        return new FlowEventTranslation(List.of(leverage, deleveraging), List.of(), null);
    }

    private FlowEventTranslation etfFlow(
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            String source,
            Map<String, Object> attributes
    ) {
        BigDecimal referenceAssets = decimalAttribute(record.attributes(), "referenceAssets", null);
        RiskObservation redemption = ratioObservation(
                record, horizon, RiskDimension.FORCED_SELLING, "A2", source, attributes,
                () -> FlowEventMetrics.redemptionProxy(record.value(), referenceAssets));
        return new FlowEventTranslation(List.of(redemption), List.of(), null);
    }

    private FlowEventTranslation substantiveEvent(
            FlowEventDataset dataset,
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
        Map<String, Object> eventPayload = new HashMap<>(attributes);
        eventPayload.put("economicMeaning", meaning.code());
        boolean adverse = adverse(dataset, record);
        if (!adverse || (record.value() != null && record.value().signum() == 0)) {
            RiskObservation zero = observation(
                    record, horizon, RiskDimension.SUBSTANTIVE_TRIGGER, indicatorCode.get(), BigDecimal.ZERO,
                    "score", source, RiskDataQualityStatus.VALID_ZERO, eventPayload);
            return new FlowEventTranslation(List.of(zero), List.of(), null);
        }
        BigDecimal severity = record.value() == null
                ? null
                : FlowEventMetrics.normalizeSeverity(record.value().abs());
        RiskObservation observation = observation(
                record, horizon, RiskDimension.SUBSTANTIVE_TRIGGER, indicatorCode.get(), severity,
                "score", source, severity == null
                ? RiskDataQualityStatus.INSUFFICIENT_HISTORY
                : RiskDataQualityStatus.AVAILABLE, eventPayload);
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
        if (dataset == FlowEventDataset.SHARE_REDUCTION && !actualReduction) {
            return new FlowEventTranslation(List.of(), List.of(), null);
        }
        boolean confirmed = actualReduction && (booleanAttribute(record.attributes(), "fundFlowConfirmed")
                || booleanAttribute(record.attributes(), "priceConfirmed"));
        Map<String, Object> payload = new HashMap<>(attributes);
        payload.put("modifierCandidate", true);
        payload.put("dimensionScoreEligible", false);
        payload.put("confirmed", confirmed);
        payload.put("scheduled", booleanAttribute(record.attributes(), "scheduled"));
        BigDecimal severity = confirmed && record.value() != null
                ? FlowEventMetrics.normalizeSeverity(record.value().abs())
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
            RiskDataQualityStatus qualityStatus,
            Map<String, Object> attributes
    ) {
        Map<String, Object> horizonAttributes = new HashMap<>(attributes);
        horizonAttributes.put("windowDays", windowDays(horizon));
        return new RiskObservation(
                record.object(), horizon, record.tradeDate(), dimension, indicatorCode, value, unit,
                record.observedAt(), record.availableAt(), source,
                qualityStatus, horizonAttributes);
    }

    private RiskObservation ratioObservation(
            FlowEventSourceRecord record,
            RiskHorizon horizon,
            RiskDimension dimension,
            String indicatorCode,
            String source,
            Map<String, Object> attributes,
            MetricCalculation calculation
    ) {
        try {
            BigDecimal value = calculation.calculate();
            RiskDataQualityStatus status = value.signum() == 0
                    ? RiskDataQualityStatus.VALID_ZERO
                    : RiskDataQualityStatus.AVAILABLE;
            return observation(record, horizon, dimension, indicatorCode, value, "ratio", source, status, attributes);
        } catch (FlowEventMetrics.InsufficientHistoryException exception) {
            Map<String, Object> insufficientAttributes = new HashMap<>(attributes);
            insufficientAttributes.put("qualityReason", exception.getMessage());
            return observation(record, horizon, dimension, indicatorCode, null, "ratio", source,
                    RiskDataQualityStatus.INSUFFICIENT_HISTORY, insufficientAttributes);
        }
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

    private boolean adverse(FlowEventDataset dataset, FlowEventSourceRecord record) {
        if (record.attributes().containsKey("adverse")) {
            return booleanAttribute(record.attributes(), "adverse");
        }
        if (dataset == FlowEventDataset.EARNINGS_FORECAST) {
            return record.value() != null && record.value().signum() < 0;
        }
        return true;
    }

    private int windowDays(RiskHorizon horizon) {
        return switch (horizon) {
            case SHORT_TERM -> 5;
            case MEDIUM_TERM -> 20;
            case LONG_TERM -> 60;
        };
    }

    @FunctionalInterface
    private interface MetricCalculation {
        BigDecimal calculate();
    }
}
