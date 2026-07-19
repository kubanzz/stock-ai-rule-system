package com.jx.tracker.risk.provider;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 风险事件事实。occurredAt 表示事件实际或计划生效时间，允许晚于首次观测时间；
 * observedAt 表示数据源首次观测时间，availableAt 表示系统可用于计算的时间。
 */
public record RiskEvent(
        RiskObjectKey object,
        LocalDate tradeDate,
        RiskDimension dimension,
        String eventType,
        String eventKey,
        BigDecimal severityScore,
        LocalDateTime occurredAt,
        LocalDateTime observedAt,
        LocalDateTime availableAt,
        String source,
        RiskDataQualityStatus qualityStatus,
        Map<String, Object> payload
) {

    public RiskEvent {
        if (object == null || tradeDate == null || dimension == null || occurredAt == null
                || observedAt == null || availableAt == null || qualityStatus == null) {
            throw new IllegalArgumentException("event object, date, dimension, timestamps and qualityStatus are required");
        }
        if (eventType == null || eventType.isBlank() || eventKey == null || eventKey.isBlank()
                || source == null || source.isBlank()) {
            throw new IllegalArgumentException("eventType, eventKey and source must not be blank");
        }
        if (severityScore != null
                && (severityScore.signum() < 0 || severityScore.compareTo(new BigDecimal("100")) > 0)) {
            throw new IllegalArgumentException("severityScore must be between 0 and 100");
        }
        if (availableAt.isBefore(observedAt)) {
            throw new IllegalArgumentException("event timestamps must satisfy observedAt <= availableAt");
        }
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
