package com.jx.tracker.risk.migration;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.workflow.JdbcRiskWorkflowRepository;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
            assertSignalHistoryOnlyAppendsContentChanges(schema);
            assertConcurrentFirstSignalWriteIsIdempotent(schema);
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
            execute(schema, """
                    INSERT INTO stock_signal_daily (
                        symbol, signal_date, `signal`, signal_level,
                        bullish_score, bearish_score, risk_score, confidence,
                        triggered_rules, explanation, risk_disclaimer, created_at
                    ) VALUES
                        ('600519.SH', '2020-01-02', 'high_risk', '历史高风险',
                         80, 20, 90, 0.80, JSON_ARRAY('LEGACY_1'), '历史一', '辅助决策',
                         '2020-01-02 18:00:00'),
                        ('000001.SZ', '2020-01-02', 'bearish', '历史看跌',
                         10, 70, 30, 0.70, JSON_ARRAY('LEGACY_2'), '历史二', '辅助决策',
                         '2020-01-02 18:05:00')
                    """);
            execute(schema, "DROP TABLE flyway_schema_history");

            Flyway flyway = flyway(schema);
            flyway.baseline();
            LocalDateTime migrationStartedAt = databaseNow(schema);
            MigrateResult upgraded = flyway.migrate();
            LocalDateTime migrationFinishedAt = databaseNow(schema);

            assertThat(upgraded.migrationsExecuted).isEqualTo(1);
            assertThat(currentVersion(schema)).isEqualTo("2");
            assertJsonCheckpointRoundTrip(schema);
            assertLayeredEvidenceDoesNotOverwrite(schema);
            assertExposureRevisionIsMonotonic(schema);
            assertSeedUsesOneConservativeMigrationTime(schema, migrationStartedAt, migrationFinishedAt);
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

    private void assertSeedUsesOneConservativeMigrationTime(
            String schema,
            LocalDateTime migrationStartedAt,
            LocalDateTime migrationFinishedAt
    ) {
        JdbcTemplate jdbc = jdbc(schema);
        Map<String, Object> seed = jdbc.queryForMap("""
                SELECT COUNT(*) AS version_count,
                       COUNT(DISTINCT available_at) AS available_time_count,
                       MIN(available_at) AS available_at,
                       SUM(content_fingerprint = formal.signal_content_fingerprint) AS matching_fingerprints,
                       SUM(history.signal_direction = CASE
                           WHEN history.symbol = '600519.SH' THEN 'bullish'
                           ELSE 'bearish'
                       END) AS matching_directions
                FROM stock_signal_daily_history history
                JOIN stock_signal_daily formal ON formal.id = history.signal_id
                """);
        Object rawAvailableAt = seed.get("available_at");
        LocalDateTime availableAt = rawAvailableAt instanceof LocalDateTime localDateTime
                ? localDateTime
                : ((java.sql.Timestamp) rawAvailableAt).toLocalDateTime();

        assertThat(seed.get("version_count")).asString().isEqualTo("2");
        assertThat(seed.get("available_time_count")).asString().isEqualTo("1");
        assertThat(seed.get("matching_fingerprints")).asString().isEqualTo("2");
        assertThat(seed.get("matching_directions")).asString().isEqualTo("2");
        assertThat(availableAt).isBetween(migrationStartedAt, migrationFinishedAt);
        assertThat(availableAt).isAfter(LocalDateTime.of(2020, 1, 2, 18, 5));
    }

    private void assertSignalHistoryOnlyAppendsContentChanges(String schema) throws Exception {
        SqlSessionFactory sessionFactory = signalSessionFactory(schema);
        LocalDate signalDate = LocalDate.of(2026, 7, 18);

        writeSignalVersion(sessionFactory, signal("600519.SH", signalDate, "bullish", "0.75"), signalDate.atTime(18, 0));
        writeSignalVersion(sessionFactory, signal("600519.SH", signalDate, "bullish", "0.75"), signalDate.atTime(18, 1));
        writeSignalVersion(sessionFactory, signal("600519.SH", signalDate, "bearish", "0.80"), signalDate.atTime(18, 2));
        writeSignalVersion(sessionFactory, signal("600519.SH", signalDate, "bullish", "0.75"), signalDate.atTime(18, 3));

        List<Map<String, Object>> versions = jdbc(schema).queryForList("""
                SELECT version_no, signal_direction, available_at,
                       LENGTH(content_fingerprint) AS fingerprint_length
                FROM stock_signal_daily_history
                WHERE symbol = '600519.SH' AND signal_date = '2026-07-18'
                ORDER BY version_no
                """);
        assertThat(versions).extracting(row -> row.get("signal_direction"))
                .containsExactly("bullish", "bearish", "bullish");
        assertThat(versions).extracting(row -> row.get("version_no").toString())
                .containsExactly("1", "2", "3");
        assertThat(versions).extracting(row -> row.get("fingerprint_length").toString())
                .containsOnly("64");
    }

    private void assertConcurrentFirstSignalWriteIsIdempotent(String schema) throws Exception {
        SqlSessionFactory sessionFactory = signalSessionFactory(schema);
        LocalDate signalDate = LocalDate.of(2026, 7, 18);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> writes = List.of(
                    executor.submit(() -> concurrentSignalWrite(sessionFactory, ready, start, signalDate)),
                    executor.submit(() -> concurrentSignalWrite(sessionFactory, ready, start, signalDate))
            );
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> write : writes) {
                write.get(20, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        JdbcTemplate jdbc = jdbc(schema);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_signal_daily
                WHERE symbol = '000001.SZ' AND signal_date = '2026-07-18'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM stock_signal_daily_history
                WHERE symbol = '000001.SZ' AND signal_date = '2026-07-18'
                """, Integer.class)).isEqualTo(1);
    }

    private Void concurrentSignalWrite(
            SqlSessionFactory sessionFactory,
            CountDownLatch ready,
            CountDownLatch start,
            LocalDate signalDate
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
        writeSignalVersion(
                sessionFactory,
                signal("000001.SZ", signalDate, "bullish", "0.65"),
                signalDate.atTime(18, 0)
        );
        return null;
    }

    private void writeSignalVersion(
            SqlSessionFactory sessionFactory,
            StockSignalDaily signal,
            LocalDateTime availableAt
    ) throws Exception {
        try (SqlSession session = sessionFactory.openSession(false)) {
            StockSignalDailyMapper mapper = session.getMapper(StockSignalDailyMapper.class);
            try {
                mapper.upsertSignal(signal);
                StockSignalDaily persisted = mapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                        .eq(StockSignalDaily::getSymbol, signal.getSymbol())
                        .eq(StockSignalDaily::getSignalDate, signal.getSignalDate()));
                assertThat(persisted).isNotNull();
                mapper.insertSignalHistoryIfChanged(persisted.getId(), availableAt);
                session.commit();
            } catch (Exception exception) {
                session.rollback();
                throw exception;
            }
        }
    }

    private StockSignalDaily signal(String symbol, LocalDate signalDate, String direction, String confidence) {
        boolean bullish = "bullish".equals(direction);
        return StockSignalDaily.builder()
                .symbol(symbol)
                .signalDate(signalDate)
                .signal(direction)
                .signalDirection(direction)
                .signalLevel(bullish ? "偏看涨" : "偏看跌")
                .bullishScore(new BigDecimal(bullish ? "65" : "10"))
                .bearishScore(new BigDecimal(bullish ? "10" : "80"))
                .riskScore(BigDecimal.ZERO)
                .confidence(new BigDecimal(confidence))
                .triggeredRules("[\"MYSQL_PIT\"]")
                .explanation(bullish ? "内容看涨" : "内容看跌")
                .riskDisclaimer("辅助决策，不构成投资建议")
                .build();
    }

    private SqlSessionFactory signalSessionFactory(String schema) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment(
                "mysql-signal-pit",
                new JdbcTransactionFactory(),
                new DriverManagerDataSource(schemaUrl(schema), username, password)
        ));
        configuration.addMapper(StockSignalDailyMapper.class);
        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(schemaUrl(schema), username, password));
    }

    private LocalDateTime databaseNow(String schema) {
        return jdbc(schema).queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
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
