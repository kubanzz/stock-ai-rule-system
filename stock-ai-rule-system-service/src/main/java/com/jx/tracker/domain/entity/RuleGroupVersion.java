package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rule_group_version")
public class RuleGroupVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long groupId;
    private String versionNo;
    private String snapshotJson;
    @TableField("created_at")
    private LocalDateTime createdAt;
}
