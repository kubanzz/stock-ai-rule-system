package com.jx.tracker.risk.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.workflow.JdbcRiskWorkflowRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RISK_MYSQL_IT", matches = "true")
class RiskMySqlMigrationIntegrationTest {

    private static final Pattern SAFE_SCHEMA = Pattern.compile("risk_smoke_[0-9]{14}_[0-9]+");
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();

    private final String serverUrl = System.getenv().getOrDefault(
            "RISK_MYSQL_IT_SERVER_URL",
            "jdbc:mysql://127.0.0.1:3306"
    );
    private final String username = System.getenv().getOrDefault("RISK_MYSQL_IT_USERNAME", "root");
    private final String password = System.getenv().getOrDefault("RISK_MYSQL_IT_PASSWORD", "");

    @Test
    void migratesEmptySchemaAndRemainsIdempotentWithMySqlJson() throws Exception {
        String schema = schemaName();
        createSchema(schema);
        try {
            Flyway flyway = flyway(schema);
            MigrateResult first = flyway.migrate();
            MigrateResult repeated = flyway.migrate();

            assertThat(first.migrationsExecuted).isEqualTo(2);
            assertThat(repeated.migrationsExecuted).isZero();
            assertJsonCheckpointRoundTrip(schema);
            assertLayeredEvidenceDoesNotOverwrite(schema);
            assertExposureRevisionIsMonotonic(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void explicitlyBaselinesExistingVersionOneBeforeApplyingVersionTwo() throws Exception {
        String schema = schemaName();
        createSchema(schema);
        try {
            Flyway.configure()
                    .dataSource(schemaUrl(schema), username, password)
                    .locations("classpath:db/migration")
                    .target("1")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            execute(schema, "DROP TABLE flyway_schema_history");

            Flyway flyway = flyway(schema);
            flyway.baseline();
            MigrateResult upgraded = flyway.migrate();

            assertThat(upgraded.migrationsExecuted).isEqualTo(1);
            assertThat(currentVersion(schema)).isEqualTo("2");
            assertJsonCheckpointRoundTrip(schema);
            assertLayeredEvidenceDoesNotOverwrite(schema);
            assertExposureRevisionIsMonotonic(schema);
        } finally {
            dropSchema(schema);
        }
    }

    private Flyway flyway(String schema) {
        return Flyway.configure()
                .dataSource(schemaUrl(schema), username, password)
                .locations("classpath:db/migration")
                .baselineVersion("1")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    private void assertJsonCheckpointRoundTrip(String schema) throws Exception {
        String sql = """
                INSERT INTO risk_ingestion_checkpoint (
                    provider_code, dataset_code, scope_key, checkpoint_value,
                    checkpoint_at, observed_at, available_at, source, quality_status
                ) VALUES (?, ?, ?, CAST(? AS JSON), NOW(3), NOW(3), NOW(3), ?, ?)
                ON DUPLICATE KEY UPDATE checkpoint_value = VALUES(checkpoint_value)
                """;
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, "mysql-smoke");
            statement.setString(2, "migration");
            statement.setString(3, "CN-A");
            statement.setString(4, "{\"cursor\":\"2026-07-18\"}");
            statement.setString(5, "integration-test");
            statement.setString(6, "available");
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT JSON_UNQUOTE(JSON_EXTRACT(checkpoint_value, '$.cursor'))
                     FROM risk_ingestion_checkpoint
                     WHERE provider_code = 'mysql-smoke'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("2026-07-18");
        }
    }

    private void assertLayeredEvidenceDoesNotOverwrite(String schema) throws Exception {
        execute(schema, """
                INSERT INTO risk_score_snapshot (
                    object_type, object_id, horizon, trade_date,
                    v_score, t_score, s_score, c_score, a_score, m_score, total_score,
                    risk_level, risk_stage, completeness, risk_confidence, model_version,
                    observed_at, available_at, source, quality_status, calculated_at
                ) VALUES (
                    'stock', '600519.SH', '1-5d', '2026-07-18',
                    80, 80, 80, 80, 80, 1.00, 80,
                    'critical', 'stampede', 1.00, 1.00, 'mysql-smoke',
                    '2026-07-18 15:00:00.000', '2026-07-18 15:00:00.000',
                    'integration-test', 'available', '2026-07-18 20:00:00.000'
                )
                """);
        String sql = """
                INSERT INTO risk_score_evidence (
                    snapshot_id, layer_object_type, layer_object_id,
                    dimension_code, indicator_code, raw_value, indicator_score,
                    weighted_contribution, observed_at, available_at,
                    source, quality_status, evidence_json
                )
                SELECT id, ?, ?, 'V', 'V1', 1, 80, 16,
                       '2026-07-18 15:00:00.000', '2026-07-18 15:00:00.000',
                       'aktools', 'available', JSON_OBJECT('layer', ?)
                FROM risk_score_snapshot
                WHERE object_type = 'stock' AND object_id = '600519.SH'
                  AND horizon = '1-5d' AND trade_date = '2026-07-18'
                  AND model_version = 'mysql-smoke'
                """;
        try (Connection connection = connection(schema);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            insertLayer(statement, "market", "CN-A");
            insertLayer(statement, "sector", "SW1:801120");
            insertLayer(statement, "stock", "600519.SH");
        }
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT COUNT(*)
                     FROM risk_score_evidence evidence
                     JOIN risk_score_snapshot snapshot ON snapshot.id = evidence.snapshot_id
                     WHERE snapshot.object_id = '600519.SH'
                       AND snapshot.model_version = 'mysql-smoke'
                       AND evidence.indicator_code = 'V1'
                       AND evidence.source = 'aktools'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
    }

    private void assertExposureRevisionIsMonotonic(String schema) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                schemaUrl(schema), username, password));
        JdbcRiskWorkflowRepository repository = new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper());
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        RiskObjectKey stock = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
        RiskObjectKey sector = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780");
        IndustryExposure older = new IndustryExposure(
                stock, sector, tradeDate.minusYears(1), null,
                tradeDate.minusYears(1).atTime(18, 0), tradeDate.minusYears(1).atTime(19, 0),
                "aktools", RiskDataQualityStatus.AVAILABLE);
        IndustryExposure newer = new IndustryExposure(
                stock, sector, tradeDate.minusYears(1), tradeDate.minusDays(1),
                tradeDate.atTime(18, 0), tradeDate.atTime(19, 0),
                "aktools", RiskDataQualityStatus.INSUFFICIENT_HISTORY);

        repository.saveIndustryExposure(older);
        repository.saveIndustryExposure(newer);
        Map<String, Object> forward = exposureRevision(jdbc);
        jdbc.update("DELETE FROM risk_object_exposure");
        repository.saveIndustryExposure(newer);
        repository.saveIndustryExposure(older);
        Map<String, Object> reverse = exposureRevision(jdbc);

        assertThat(reverse).isEqualTo(forward);
        assertThat(reverse.get("valid_to").toString()).isEqualTo(tradeDate.minusDays(1).toString());
        assertThat(reverse.get("quality_status")).isEqualTo("insufficient_history");
        assertThat(reverse.get("observed_at")).isEqualTo(tradeDate.atTime(18, 0));
        assertThat(reverse.get("available_at")).isEqualTo(tradeDate.atTime(19, 0));
    }

    private Map<String, Object> exposureRevision(JdbcTemplate jdbc) {
        return jdbc.queryForMap("""
                SELECT valid_to, observed_at, available_at, quality_status
                FROM risk_object_exposure
                WHERE object_type = 'stock' AND object_id = '600519.SH'
                  AND parent_object_type = 'sector' AND parent_object_id = 'SW1:801780'
                """);
    }

    private void insertLayer(PreparedStatement statement, String objectType, String objectId) throws Exception {
        statement.setString(1, objectType);
        statement.setString(2, objectId);
        statement.setString(3, objectType + ":" + objectId);
        assertThat(statement.executeUpdate()).isEqualTo(1);
    }

    private String currentVersion(String schema) throws Exception {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success = 1 AND version IS NOT NULL
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void createSchema(String schema) throws Exception {
        requireSafeSchema(schema);
        try (Connection connection = connection(null);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
    }

    private void dropSchema(String schema) throws Exception {
        requireSafeSchema(schema);
        try (Connection connection = connection(null);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + schema + "`");
        }
    }

    private void execute(String schema, String sql) throws Exception {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private Connection connection(String schema) throws Exception {
        return DriverManager.getConnection(
                schema == null ? serverUrl : schemaUrl(schema),
                username,
                password
        );
    }

    private String schemaUrl(String schema) {
        requireSafeSchema(schema);
        return serverUrl + "/" + schema
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai";
    }

    private String schemaName() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        long suffix = ProcessHandle.current().pid() * 10L + SCHEMA_SEQUENCE.incrementAndGet();
        String schema = "risk_smoke_" + timestamp + "_" + suffix;
        requireSafeSchema(schema);
        return schema;
    }

    private void requireSafeSchema(String schema) {
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("拒绝操作非临时风险测试 schema");
        }
    }
}
