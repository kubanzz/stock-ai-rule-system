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
                "component_code VARCHAR(64) NOT NULL",
                "CREATE TABLE risk_event_fact",
                "CREATE TABLE risk_score_snapshot",
                "CREATE TABLE risk_score_evidence",
                "CREATE TABLE risk_gate_result",
                "CREATE TABLE risk_ingestion_checkpoint",
                "observed_at DATETIME(3)",
                "available_at DATETIME(3)",
                "quality_status VARCHAR(32)",
                "evidence_json JSON",
                "layer_object_type VARCHAR(16) NOT NULL",
                "layer_object_id VARCHAR(64) NOT NULL",
                "UNIQUE KEY uk_risk_score_snapshot_object_horizon_date_model",
                "UNIQUE KEY uk_risk_score_evidence_snapshot_layer_indicator_source",
                "object_type, object_id, horizon, trade_date, indicator_code, component_code, available_at, source",
                "CHECK (total_score IS NULL OR (total_score >= 0 AND total_score <= 100))",
                "CHECK (m_score IS NULL OR (m_score >= 0.90 AND m_score <= 1.20))",
                "CHECK (risk_confidence IS NULL OR (risk_confidence >= 0 AND risk_confidence <= 1))",
                "CHECK ((total_score IS NULL AND risk_level IS NULL AND risk_stage IS NULL AND risk_confidence IS NULL) OR (total_score IS NOT NULL AND risk_level IS NOT NULL AND risk_stage IS NOT NULL AND risk_confidence IS NOT NULL AND completeness >= 0.80))",
                "original_confidence DECIMAL(6,5) NOT NULL",
                "suggested_confidence DECIMAL(6,5) NOT NULL",
                "suggested_action VARCHAR(16) NOT NULL",
                "CHECK (original_confidence >= 0 AND original_confidence <= 1)",
                "CHECK (suggested_confidence >= 0 AND suggested_confidence <= 1)"
        );
    }

    @Test
    void currentIndustryMetadataMigrationAddsTheDisplayNameWithoutRewritingHistory() throws IOException {
        String migration = resource("/db/migration/V3__risk_current_industry_metadata.sql");

        assertThat(migration).contains(
                "ALTER TABLE risk_object_exposure",
                "ADD COLUMN parent_object_name VARCHAR(128) NULL"
        );
        assertThat(migration).doesNotContain("UPDATE risk_object_exposure");
    }

    @Test
    void observationTierMigrationCreatesCompactBaselineAndColdArchiveWithoutMovingData() throws IOException {
        String migration = resource("/db/migration/V4__risk_observation_storage_tiers.sql");

        assertThat(migration).contains(
                "CREATE TABLE risk_indicator_baseline",
                "actual_value DECIMAL(30,10) NULL",
                "UNIQUE KEY uk_risk_baseline_object_component_date",
                "object_type, object_id, horizon, trade_date, indicator_code,\n        component_code, available_at, source",
                "KEY idx_risk_baseline_object_horizon_date",
                "CREATE TABLE risk_indicator_observation_archive",
                "PRIMARY KEY (id)",
                "UNIQUE KEY uk_risk_archive_object_code_date_source",
                "KEY idx_risk_archive_object_date",
                "KEY idx_risk_archive_available_at"
        );
        assertThat(migration).doesNotContain(
                "INSERT INTO risk_indicator_baseline",
                "INSERT INTO risk_indicator_observation_archive",
                "DELETE FROM risk_indicator_observation",
                "UPDATE risk_indicator_observation"
        );
        assertThat(migration).contains("轻量时点修订");
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

    @Test
    void signalMigrationCreatesAppendOnlyPointInTimeHistoryAndSeedsExistingRows() throws IOException {
        String migration = resource("/db/migration/V2__risk_warning_foundation.sql");

        assertThat(migration).contains(
                "CREATE TABLE stock_signal_daily_history",
                "signal_id BIGINT NOT NULL",
                "signal_direction VARCHAR(16) NOT NULL",
                "available_at DATETIME(3) NOT NULL",
                "content_fingerprint CHAR(64) CHARACTER SET ascii NOT NULL",
                "UNIQUE KEY uk_stock_signal_daily_history_version (signal_id, version_no)",
                "KEY idx_stock_signal_daily_history_pit (signal_date, symbol, available_at, id)",
                "INSERT INTO stock_signal_daily_history",
                "SELECT id, 1, symbol, signal_date, `signal`, signal_direction",
                "CURRENT_TIMESTAMP(3)"
        );
        assertThat(migration).doesNotContain("COALESCE(created_at, CURRENT_TIMESTAMP(3))");
    }

    @Test
    void productionRuleMigrationConvertsSeededJsonDefinitionsWithoutChangingCandidateDsl() throws IOException {
        String migration = resource("/db/migration/V8__migrate_json_rules_to_drools.sql");

        assertThat(migration).contains(
                "SET rule_format = 'drools'",
                "$f.matches",
                "CR_TREND_BEAR_GUARD_001",
                "CR_OVERSOLD_RISK_GUARD_001",
                "CR_SIDEWAYS_MACD_RECOVERY_001",
                "CR_VOLUME_MACD_DIVERGENCE_001",
                "CR_WEAK_VOLUME_CONTINUATION_001",
                "AND rule_format = 'json'",
                "candidate_rule.proposed_content");
        assertThat(migration).doesNotContain("DELETE FROM rule_definition");
    }

    @Test
    void candidateRuleMigrationRemovesOnlyLegacyUnpublishedProductionPlaceholders() throws IOException {
        String migration = resource("/db/migration/V10__separate_candidate_rule_definitions.sql");

        assertThat(migration).contains(
                "DELETE rd",
                "INNER JOIN candidate_rule cr",
                "rd.created_by = 'historical-review'",
                "rd.status = 'draft'",
                "rd.enabled = 0",
                "cr.status NOT IN ('published', 'active')",
                "NOT EXISTS",
                "FROM rule_version rv"
        );
        assertThat(migration).contains("candidate_rule.proposed_content").doesNotContain("DELETE FROM candidate_rule");
    }

    @Test
    void duplicateRuleMigrationPreservesPublishedHistoryAndOnlyDisablesUntouchedSeeds() throws IOException {
        String migration = resource("/db/migration/V11__disable_superseded_seed_rules.sql");

        assertThat(migration).contains(
                "seed.created_by = 'system-seed'",
                "seed.updated_by = 'system-seed'",
                "SHA2(seed.rule_content, 256) = duplicate_pair.content_sha256",
                "FROM rule_version version WHERE version.rule_id = seed.id",
                "candidate.status = 'published'",
                "candidate.approval_status = 'approved'",
                "candidate.backtest_status = 'success'",
                "JSON_SEARCH(strategy.snapshot_json, 'one', seed.rule_code)",
                "seed.status = 'disabled'",
                "seed.enabled = 0",
                "INSERT INTO rule_operation_log");
        assertThat(migration).doesNotContain("DELETE FROM rule_definition", "DELETE FROM candidate_rule");
    }

    @Test
    void duplicateRuleCorrectionAuditsChangedReplacementsWithoutReactivation() throws IOException {
        String migration = resource("/db/migration/V12__audit_changed_duplicate_replacements.sql");

        assertThat(migration).contains(
                "seed.updated_by = 'rule-dedup-v11'",
                "SHA2(seed.rule_content, 256) = replacement.seed_sha256",
                "FROM rule_version version WHERE version.rule_id = seed.id",
                "log.operation = 'disable_duplicate'",
                "published.status = 'active'",
                "published.enabled = 1",
                "candidate.status = 'published'",
                "COALESCE(SHA2(published.rule_content, 256), '') <> replacement.published_sha256",
                "review_changed_replacement",
                "'disabled', 'disabled'"
        );
        assertThat(migration).doesNotContain(
                "UPDATE rule_definition", "DELETE FROM rule_definition", "DELETE FROM candidate_rule",
                "'disabled', 'active'");
    }

    @Test
    void publishedCandidateMetadataMigrationBackfillsOnlyMissingDefaults() throws IOException {
        String migration = resource("/db/migration/V13__backfill_published_candidate_rule_metadata.sql");

        assertThat(migration).contains(
                "candidate.status = 'published'",
                "candidate.target_rule_code = rule.rule_code",
                "CR_TREND_BEAR_GUARD_001",
                "CR_OVERSOLD_RISK_GUARD_001",
                "CR_SIDEWAYS_MACD_RECOVERY_001",
                "CR_VOLUME_MACD_DIVERGENCE_001",
                "CR_WEAK_VOLUME_CONTINUATION_001",
                "rule.rule_name IS NULL OR TRIM(rule.rule_name) = '' OR rule.rule_name = rule.rule_code",
                "rule.description IS NULL OR TRIM(rule.description) = ''",
                "rule.rule_type = 'ai_candidate'",
                "仅供研究和辅助决策，不构成投资建议。"
        );
        assertThat(migration).doesNotContain(
                "SET rule.rule_content", "rule.version =", "rule.status =", "rule.enabled =",
                "DELETE FROM rule_definition", "DELETE FROM candidate_rule");
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
