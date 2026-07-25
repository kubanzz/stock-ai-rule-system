package com.jx.tracker.risk.persistence.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 查询层暂定评估使用的实际观测值。actualValue 可来自正式值，也可来自 auditValue，
 * 但后者不会回写正式评分表。
 */
@Data
public class RiskProvisionalObservationRow {

    private String objectType;
    private String objectId;
    private String horizon;
    private LocalDate tradeDate;
    private String dimensionCode;
    private String indicatorCode;
    private String componentCode;
    private BigDecimal actualValue;
    private LocalDateTime observedAt;
    private LocalDateTime availableAt;
    private String source;
    private String qualityStatus;
}
