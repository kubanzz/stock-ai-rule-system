package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("rule_strategy")
public class RuleStrategy {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String strategyCode;
    private String strategyName;
    private String description;
    private String version;
    private String status;
    private BigDecimal bullishThreshold;
    private BigDecimal bearishThreshold;
    private BigDecimal riskThreshold;
    private String snapshotJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
