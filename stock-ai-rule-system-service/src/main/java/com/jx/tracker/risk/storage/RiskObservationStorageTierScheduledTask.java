package com.jx.tracker.risk.storage;

import com.jx.tracker.risk.runtime.RiskStorageTierProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 默认不注册；仅在显式开启归档开关后按小批次执行。 */
public class RiskObservationStorageTierScheduledTask {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            RiskObservationStorageTierScheduledTask.class);

    private final RiskObservationStorageTierService service;
    private final RiskStorageTierProperties properties;

    public RiskObservationStorageTierScheduledTask(
            RiskObservationStorageTierService service,
            RiskStorageTierProperties properties
    ) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(
            cron = "${stock-ai-rule.risk-warning.storage.archive-cron:0 30 2 * * *}",
            zone = "${stock-ai-rule.risk-warning.storage.archive-zone:Asia/Shanghai}"
    )
    public void archiveExpiredObservations() {
        int total = 0;
        int batches = properties.requiredMaxBatchesPerRun();
        for (int index = 0; index < batches; index++) {
            var result = service.archiveExpiredBatch();
            total += result.archivedRows();
            if (result.archivedRows() == 0) {
                break;
            }
        }
        if (total > 0) {
            LOGGER.info("风险观测冷热分层完成，本轮归档 {} 行", total);
        }
    }
}
