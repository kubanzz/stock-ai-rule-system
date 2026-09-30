package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rule_strategy_version")
public class RuleStrategyVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    private String versionNo;
    private String snapshotJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
