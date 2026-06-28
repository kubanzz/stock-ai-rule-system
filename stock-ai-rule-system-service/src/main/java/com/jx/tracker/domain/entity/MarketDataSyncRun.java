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
@TableName("market_data_sync_run")
@Schema(name = "行情同步运行记录表")
public class MarketDataSyncRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String dataSource;

    private String syncType;

    private String status;

    private String requestParams;

    private String targetSymbol;

    private LocalDate startDate;

    private LocalDate endDate;

    private String triggerType;

    private String triggerBy;

    private Integer scanned;

    private Integer inserted;

    private Integer updated;

    private Integer skipped;

    private Integer failed;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;
}
