package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("rule_definition")
@Schema(name = "规则表")
public class RuleDefinition {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ruleCode;

    private String ruleName;

    private String ruleType;

    private String ruleContent;

    private String ruleFormat;

    private String version;

    private String status;

    private Long currentVersionId;

    private String currentVersionNo;

    private Boolean enabled;

    private Integer priority;

    private String createdBy;

    private String updatedBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
