package com.jx.tracker.risk.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("risk_score_snapshot")
public class RiskScoreSnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String objectType;
    private String objectId;
    private String horizon;
    private LocalDate tradeDate;
    private BigDecimal vScore;
    private BigDecimal tScore;
    private BigDecimal sScore;
    private BigDecimal cScore;
    private BigDecimal aScore;
    private BigDecimal mScore;
    private BigDecimal totalScore;
    private String riskLevel;
    private String riskStage;
    private BigDecimal completeness;
    private BigDecimal riskConfidence;
    private String modelVersion;
    private LocalDateTime observedAt;
    private LocalDateTime availableAt;
    private String source;
    private String qualityStatus;
    private LocalDateTime calculatedAt;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private String objectName;
}
