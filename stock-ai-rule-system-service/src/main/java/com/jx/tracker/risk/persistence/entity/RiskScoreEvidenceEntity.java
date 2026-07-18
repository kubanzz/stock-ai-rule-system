package com.jx.tracker.risk.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("risk_score_evidence")
public class RiskScoreEvidenceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long snapshotId;
    private String dimensionCode;
    private String indicatorCode;
    private BigDecimal rawValue;
    private BigDecimal indicatorScore;
    private BigDecimal weightedContribution;
    private LocalDateTime observedAt;
    private LocalDateTime availableAt;
    private String source;
    private String qualityStatus;
    private String evidenceJson;
    private LocalDateTime createdAt;
}
