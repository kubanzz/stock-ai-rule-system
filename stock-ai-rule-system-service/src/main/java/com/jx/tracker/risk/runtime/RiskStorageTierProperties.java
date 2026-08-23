package com.jx.tracker.risk.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stock-ai-rule.risk-warning.storage")
public class RiskStorageTierProperties {

    public static final int DEFAULT_HOT_RETENTION_YEARS = 2;
    public static final int DEFAULT_ARCHIVE_BATCH_SIZE = 5_000;
    public static final int DEFAULT_MAX_BATCHES_PER_RUN = 10;

    private boolean tieredReadEnabled;
    private boolean archiveEnabled;
    private int hotRetentionYears = DEFAULT_HOT_RETENTION_YEARS;
    private int archiveBatchSize = DEFAULT_ARCHIVE_BATCH_SIZE;
    private int maxBatchesPerRun = DEFAULT_MAX_BATCHES_PER_RUN;

    public boolean isTieredReadEnabled() {
        return tieredReadEnabled;
    }

    public void setTieredReadEnabled(boolean tieredReadEnabled) {
        this.tieredReadEnabled = tieredReadEnabled;
    }

    public boolean isArchiveEnabled() {
        return archiveEnabled;
    }

    public void setArchiveEnabled(boolean archiveEnabled) {
        this.archiveEnabled = archiveEnabled;
    }

    public int getHotRetentionYears() {
        return hotRetentionYears;
    }

    public void setHotRetentionYears(int hotRetentionYears) {
        this.hotRetentionYears = hotRetentionYears;
    }

    public int getArchiveBatchSize() {
        return archiveBatchSize;
    }

    public void setArchiveBatchSize(int archiveBatchSize) {
        this.archiveBatchSize = archiveBatchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public int requiredHotRetentionYears() {
        if (hotRetentionYears < 1 || hotRetentionYears > 10) {
            throw new IllegalStateException("risk storage hotRetentionYears must be between 1 and 10");
        }
        return hotRetentionYears;
    }

    public int requiredArchiveBatchSize() {
        if (archiveBatchSize < 100 || archiveBatchSize > 20_000) {
            throw new IllegalStateException("risk storage archiveBatchSize must be between 100 and 20000");
        }
        return archiveBatchSize;
    }

    public int requiredMaxBatchesPerRun() {
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > 100) {
            throw new IllegalStateException("risk storage maxBatchesPerRun must be between 1 and 100");
        }
        return maxBatchesPerRun;
    }
}
