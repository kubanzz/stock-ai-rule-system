package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.workflow.JdbcRiskWorkflowRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RISK_MYSQL_IT", matches = "true")
class RiskBackfillMySqlIntegrationTest {

    private static final Pattern SAFE_SCHEMA =
            Pattern.compile("risk_backfill_[0-9]{14}_[0-9]+");
    private static final AtomicInteger SCHEMA_SEQUENCE = new AtomicInteger();
    private static final LocalDate SCORE_START = LocalDate.of(2021, 7, 10);
    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);
    private static final LocalDateTime AS_OF = END_DATE.atTime(20, 0);

    private final String serverUrl = System.getenv().getOrDefault(
            "RISK_MYSQL_IT_SERVER_URL", "jdbc:mysql://127.0.0.1:3306");
    private final String username = System.getenv().getOrDefault(
            "RISK_MYSQL_IT_USERNAME", "root");
    private final String password = System.getenv().getOrDefault(
            "RISK_MYSQL_IT_PASSWORD", "");

    @Test
    void readinessQueriesAndCheckpointResumeUseRealMySqlJsonAndUniqueKeys() throws Exception {
        String schema = schemaName();
        createSchema(schema);
        try {
            Flyway.configure()
                    .dataSource(schemaUrl(schema), username, password)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(false)
                    .cleanDisabled(true)
                    .load().migrate();
            JdbcTemplate jdbc = jdbc(schema);
            JdbcRiskWorkflowRepository workflowRepository =
                    new JdbcRiskWorkflowRepository(jdbc, new ObjectMapper());
            RiskObjectKey stock = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
            RiskObservation observation = new RiskObservation(
                    stock, RiskHorizon.SHORT_TERM, END_DATE,
                    RiskDimension.STRUCTURAL_FRAGILITY, "V3", new BigDecimal("1"),
                    "ratio", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0),
                    "risk-derived-gateway", RiskDataQualityStatus.AVAILABLE,
                    Map.of("metric", "relativeReturn"));
            RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                    "market_daily", "stock:600519.SH", "page-1", AS_OF);
            RiskProviderBatch batch = new RiskProviderBatch(
                    "risk-derived-gateway", List.of(observation), List.of(), checkpoint,
                    RiskDataQualityStatus.AVAILABLE, null, AS_OF);

            workflowRepository.saveObservation(observation);
            workflowRepository.saveObservation(observation);
            workflowRepository.saveCheckpoint(
                    "a-share-market-risk", "market_daily", "stock:600519.SH", checkpoint, batch);
            insertMarketSnapshots(jdbc);

            RiskBackfillReadinessData data = new JdbcRiskBackfillReadinessRepository(jdbc).load(
                    "risk-v1", SCORE_START, END_DATE, AS_OF, List.of("600519.SH"));

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM risk_indicator_observation
                    WHERE object_type = 'stock' AND object_id = '600519.SH'
                      AND indicator_code = 'V3'
                    """, Integer.class)).isEqualTo(1);
            assertThat(data.observedIndicators()).containsKey("V3");
            assertThat(data.horizonSnapshots().values())
                    .allSatisfy(stats -> assertThat(stats.formalMarketCount()).isEqualTo(1));
            assertThat(data.checkpoints()).singleElement().satisfies(status -> {
                assertThat(status.datasetCode()).isEqualTo("market_daily");
                assertThat(status.qualityStatus()).isEqualTo("available");
            });
            assertThat(workflowRepository.findCheckpoint(
                    "a-share-market-risk", "market_daily", "stock:600519.SH"))
                    .get().extracting(RiskIngestionCheckpoint::cursor).isEqualTo("page-1");

            workflowRepository.saveIngestionStatus(
                    "a-share-market-risk", "market_daily", "stock:600519.SH", checkpoint,
                    RiskProviderBatch.unavailable(
                            "risk-derived-gateway", "temporary failure", AS_OF.plusMinutes(1)));
            assertThat(workflowRepository.findCheckpoint(
                    "a-share-market-risk", "market_daily", "stock:600519.SH"))
                    .get().extracting(RiskIngestionCheckpoint::cursor).isEqualTo("page-1");

            workflowRepository.saveIngestionStatus(
                    "a-share-market-risk", "market_daily", "stock:600519.SH", checkpoint,
                    RiskProviderBatch.validZero(
                            "risk-derived-gateway", null, AS_OF.plusMinutes(2)));
            assertThat(workflowRepository.findCheckpoint(
                    "a-share-market-risk", "market_daily", "stock:600519.SH")).isEmpty();
        } finally {
            dropSchema(schema);
        }
    }

    private void insertMarketSnapshots(JdbcTemplate jdbc) {
        long id = 1;
        for (RiskHorizon horizon : RiskHorizon.values()) {
            jdbc.update("""
                    INSERT INTO risk_score_snapshot (
                        id, object_type, object_id, horizon, trade_date,
                        v_score, t_score, s_score, c_score, a_score, m_score,
                        total_score, risk_level, risk_stage, completeness,
                        risk_confidence, model_version, observed_at, available_at,
                        source, quality_status, calculated_at
                    ) VALUES (?, 'market', 'CN-A', ?, ?,
                        80, 80, 80, 80, 80, 1.00,
                        80, 'warning', 'repricing', 0.90,
                        0.90, 'risk-v1', ?, ?, 'risk-engine', 'available', ?)
                    """, id++, horizon.getCode(), END_DATE,
                    END_DATE.atTime(19, 0), AS_OF, AS_OF);
        }
    }

    private JdbcTemplate jdbc(String schema) {
        return new JdbcTemplate(new DriverManagerDataSource(
                schemaUrl(schema), username, password));
    }

    private String schemaName() {
        return "risk_backfill_" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(LocalDateTime.now()) + "_" + SCHEMA_SEQUENCE.incrementAndGet();
    }

    private void createSchema(String schema) throws Exception {
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("unsafe schema name");
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
    }

    private void dropSchema(String schema) throws Exception {
        if (!SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("unsafe schema name");
        }
        try (Connection connection = DriverManager.getConnection(serverUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + schema + "`");
        }
    }

    private String schemaUrl(String schema) {
        String separator = serverUrl.contains("?") ? "&" : "?";
        return serverUrl + "/" + schema + separator
                + "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai";
    }
}
