package com.jx.tracker.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("signal_backfill_run")
public class SignalBackfillRun {

    @TableId
    private String runId;

    private String status;

    private String snapshotJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
