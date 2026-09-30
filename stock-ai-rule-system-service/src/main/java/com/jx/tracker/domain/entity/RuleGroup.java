package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rule_group")
public class RuleGroup {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String groupCode;
    private String groupName;
    private String description;
    private String version;
    private String status;
    private String aggregation;
    private Integer minMatchedRules;
    private String snapshotJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
