package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("workflow_run")
@Schema(name = "每日流程运行表")
public class WorkflowRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate bizDate;

    private String status;

    private String triggerType;

    private String triggerBy;

    private Boolean dryRun;

    private String requestParams;

    private String summary;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String errorMessage;
}
