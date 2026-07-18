package com.jx.tracker.risk.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RiskMigrationContractTest {

    @Test
    void baselineMigrationReplacesLegacySchemaResource() throws IOException {
        String baseline = resource("/db/migration/V1__baseline.sql");

        assertThat(getClass().getResource("/db/stock_ai_rule_schema.sql")).isNull();
        assertThat(baseline).contains("CREATE TABLE IF NOT EXISTS stock_signal_daily");
        assertThat(baseline).contains("UNIQUE KEY uk_stock_daily_quote_symbol_trade_date");
        assertThat(baseline).doesNotContain("BIGSERIAL", "JSONB");
    }

    @Test
    void riskMigrationDefinesAllTablesMetadataAndIdempotencyKeys() throws IOException {
        String migration = resource("/db/migration/V2__risk_warning_foundation.sql");

        assertThat(migration).contains(
                "CREATE TABLE risk_object_exposure",
                "CREATE TABLE risk_indicator_observation",
                "CREATE TABLE risk_event_fact",
                "CREATE TABLE risk_score_snapshot",
                "CREATE TABLE risk_score_evidence",
                "CREATE TABLE risk_gate_result",
                "CREATE TABLE risk_ingestion_checkpoint",
                "observed_at DATETIME(3)",
                "available_at DATETIME(3)",
                "quality_status VARCHAR(32)",
                "evidence_json JSON",
                "UNIQUE KEY uk_risk_score_snapshot_object_horizon_date_model",
                "CHECK (total_score IS NULL OR (total_score >= 0 AND total_score <= 100))",
                "CHECK (m_score IS NULL OR (m_score >= 0.90 AND m_score <= 1.20))",
                "CHECK (risk_confidence IS NULL OR (risk_confidence >= 0 AND risk_confidence <= 1))",
                "original_confidence DECIMAL(6,5) NOT NULL",
                "suggested_confidence DECIMAL(6,5) NOT NULL",
                "CHECK (original_confidence >= 0 AND original_confidence <= 1)",
                "CHECK (suggested_confidence >= 0 AND suggested_confidence <= 1)"
        );
    }

    @Test
    void scheduledEventsMayBecomeEffectiveAfterTheyAreObserved() throws IOException {
        String migration = resource("/db/migration/V2__risk_warning_foundation.sql");
        String eventTable = between(
                migration,
                "CREATE TABLE risk_event_fact",
                "CREATE TABLE risk_score_snapshot"
        );

        assertThat(eventTable)
                .contains(
                        "occurred_at DATETIME(3) NOT NULL COMMENT '事件实际或计划生效时间，可晚于首次观测时间'",
                        "observed_at DATETIME(3) NOT NULL COMMENT '数据源首次观测时间'",
                        "available_at DATETIME(3) NOT NULL COMMENT '系统可用时间'",
                        "CHECK (available_at >= observed_at)"
                )
                .doesNotContain("CHECK (observed_at >= occurred_at)");
    }

    @Test
    void gateResultRetainsAvailabilityMetadataAndFutureInformationGuard() throws IOException {
        String migration = resource("/db/migration/V2__risk_warning_foundation.sql");
        String gateTable = between(
                migration,
                "CREATE TABLE risk_gate_result",
                "CREATE TABLE risk_ingestion_checkpoint"
        );

        assertThat(gateTable).contains(
                "observed_at DATETIME(3)",
                "available_at DATETIME(3)",
                "source VARCHAR(64)",
                "quality_status VARCHAR(32)",
                "KEY idx_risk_gate_available_at (available_at)",
                "CHECK (available_at >= observed_at)"
        );
    }

    @Test
    void signalDirectionMigrationPreservesLegacySignalAndBackfillsDirection() throws IOException {
        String migration = resource("/db/migration/V2__risk_warning_foundation.sql");

        assertThat(migration).contains(
                "ADD COLUMN signal_direction VARCHAR(16)",
                "WHEN `signal` = 'high_risk'",
                "bullish_score > bearish_score",
                "bearish_score > bullish_score",
                "ELSE 'watch'"
        );
        assertThat(migration).doesNotContain("UPDATE stock_signal_daily SET `signal`");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String text, String start, String end) {
        return text.substring(text.indexOf(start), text.indexOf(end));
    }
}
