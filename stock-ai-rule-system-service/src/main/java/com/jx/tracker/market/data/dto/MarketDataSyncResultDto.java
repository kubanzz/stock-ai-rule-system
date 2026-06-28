package com.jx.tracker.market.data.dto;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class MarketDataSyncResultDto {

    private Long runId;

    private String dataSource;

    private String syncType;

    private String status;

    private String requestParams;

    private String targetSymbol;

    private LocalDate startDate;

    private LocalDate endDate;

    private String triggerType;

    private String triggerBy;

    private Integer scanned = 0;

    private Integer inserted = 0;

    private Integer updated = 0;

    private Integer skipped = 0;

    private Integer failed = 0;

    private List<String> errors = new ArrayList<>();

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;
}
