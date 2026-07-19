package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;

public final class MarketRiskRecordKeys {

    private MarketRiskRecordKeys() {
    }

    public static String observationKey(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            String indicatorCode,
            String metric
    ) {
        return String.join(":",
                object.objectType().getCode(), object.objectId(), horizon.getCode(), tradeDate.toString(),
                requireToken(indicatorCode, "indicatorCode"), requireToken(metric, "metric")
        );
    }

    public static String eventKey(
            RiskObjectKey object,
            LocalDate tradeDate,
            String eventType,
            String sourceId
    ) {
        return String.join(":",
                object.objectType().getCode(), object.objectId(), tradeDate.toString(),
                requireToken(eventType, "eventType"), requireToken(sourceId, "sourceId")
        );
    }

    private static String requireToken(String value, String field) {
        if (value == null || value.isBlank() || value.contains(":")) {
            throw new IllegalArgumentException(field + " must be a non-blank colon-free token");
        }
        return value;
    }
}
