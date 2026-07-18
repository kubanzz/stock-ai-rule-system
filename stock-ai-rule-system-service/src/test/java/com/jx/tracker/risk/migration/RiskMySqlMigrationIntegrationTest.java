package com.jx.tracker.risk.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
