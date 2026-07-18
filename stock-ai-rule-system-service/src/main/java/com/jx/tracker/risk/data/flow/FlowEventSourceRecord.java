package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/** 经适配器规范化的源记录，保留源时间与可审计属性。 */
public record FlowEventSourceRecord(
        String recordId,
        String cursor,
        RiskObjectKey object,
        LocalDate tradeDate,
        LocalDateTime occurredAt,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        BigDecimal value,
        String unit,
        String eventCode,
        String title,
        Map<String, Object> attributes
) {
    public FlowEventSourceRecord {
        if (recordId == null || recordId.isBlank() || cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("recordId and cursor must not be blank");
        }
        if (object == null || tradeDate == null || occurredAt == null || observedAt == null || availableAt == null) {
            throw new IllegalArgumentException("object, date and timestamps are required");
        }
        if (availableAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("availableAt must not be before observedAt");
        }
        if (unit == null || unit.isBlank() || eventCode == null || eventCode.isBlank()) {
            throw new IllegalArgumentException("unit and eventCode must not be blank");
        }
        title = title == null ? "" : title;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
