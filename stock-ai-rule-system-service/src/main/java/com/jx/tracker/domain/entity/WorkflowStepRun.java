package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
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
@TableName("workflow_step_run")
@Schema(name = "每日流程步骤运行表")
public class WorkflowStepRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowRunId;

    private String stepCode;

    private Integer stepOrder;

    private String status;

    private String inputParams;

    private String outputSummary;

    private Long durationMs;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorMessage;
}
