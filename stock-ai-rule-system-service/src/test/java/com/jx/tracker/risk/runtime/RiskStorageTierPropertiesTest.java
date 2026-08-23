package com.jx.tracker.risk.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskStorageTierPropertiesTest {

    @Test
    void defaultsKeepTieredReadsAndArchivingDisabled() {
        RiskStorageTierProperties properties = new RiskStorageTierProperties();

        assertThat(properties.isTieredReadEnabled()).isFalse();
        assertThat(properties.isArchiveEnabled()).isFalse();
        assertThat(properties.requiredHotRetentionYears()).isEqualTo(2);
        assertThat(properties.requiredArchiveBatchSize()).isEqualTo(5_000);
        assertThat(properties.requiredMaxBatchesPerRun()).isEqualTo(10);
    }

    @Test
    void rejectsUnboundedRetentionAndBatchSettings() {
        RiskStorageTierProperties properties = new RiskStorageTierProperties();
        properties.setHotRetentionYears(0);
        properties.setArchiveBatchSize(50_000);
        properties.setMaxBatchesPerRun(0);

        assertThatThrownBy(properties::requiredHotRetentionYears)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hotRetentionYears");
        assertThatThrownBy(properties::requiredArchiveBatchSize)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("archiveBatchSize");
        assertThatThrownBy(properties::requiredMaxBatchesPerRun)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxBatchesPerRun");
    }
}
