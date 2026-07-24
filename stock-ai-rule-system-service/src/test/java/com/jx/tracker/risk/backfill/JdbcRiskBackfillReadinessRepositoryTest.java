package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.model.RiskHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRiskBackfillReadinessRepositoryTest {

    private static final LocalDate SCORE_START = LocalDate.of(2021, 7, 10);
    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);
    private static final LocalDateTime AS_OF = END_DATE.atTime(20, 0);

    private JdbcTemplate jdbcTemplate;
    private JdbcRiskBackfillReadinessRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_backfill_readiness;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        createSchema();
        repository = new JdbcRiskBackfillReadinessRepository(jdbcTemplate);
    }

    @Test
    void loadsOnlyRealPointInTimeEvidenceForTheRequestedScope() {
        insertExposure();
        insertObservation("stock", "600519.SH", "C2", "advanceRatio", SCORE_START,
                "available", "1", SCORE_START.atTime(18, 0), SCORE_START.atTime(19, 0));
        insertObservation("stock", "600519.SH", "C2", "newHighLowBalance", SCORE_START,
                "available", "1", SCORE_START.atTime(18, 0), SCORE_START.atTime(19, 0));
        insertObservation("stock", "600519.SH", "C2", "aboveMovingAverageRatio", END_DATE,
                "valid_zero", "0", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertObservation("sector", "SW1:801780", "S1", "standardizedLeadingReturn", END_DATE,
                "available", "1", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertObservation("stock", "600519.SH", "V3", "V3", SCORE_START,
                "available", "1", SCORE_START.atTime(18, 0), SCORE_START.atTime(19, 0));
        insertObservation("stock", "600519.SH", "V3", "V3", END_DATE,
                "available", "1", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertObservation("market", "CN-A", "V3", "V3", SCORE_START,
                "available", "1", SCORE_START.atTime(18, 0), SCORE_START.atTime(19, 0));
        insertObservation("market", "CN-A", "V3", "V3", END_DATE,
                "available", "1", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertObservation("stock", "000001.SZ", "T1", "T1", END_DATE,
                "available", "1", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertObservation("stock", "600519.SH", "A1", "A1", END_DATE,
                "available", "1", END_DATE.atTime(18, 0), AS_OF.plusMinutes(1));
        insertObservation("stock", "600519.SH", "A2", "A2", END_DATE,
                "stale", "1", END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertObservation("stock", "600519.SH", "V1", "peTtm", END_DATE,
                "available", null, END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        insertSnapshots();
        insertAuditRows();

        RiskBackfillReadinessData data = repository.load(
                "risk-v1", SCORE_START, END_DATE, AS_OF, List.of("600519.SH"));

        assertThat(data.observedIndicators()).containsOnlyKeys("C2", "S1", "V3");
        assertThat(data.observedIndicators().get("C2").componentCodes())
                .containsExactlyInAnyOrder(
                        "advanceRatio", "newHighLowBalance", "aboveMovingAverageRatio");
        assertThat(data.observedIndicators().get("C2").observationCount()).isEqualTo(3);
        assertThat(data.coreEarliestDate()).isEqualTo(SCORE_START);
        assertThat(data.coreLatestDate()).isEqualTo(END_DATE);
        assertThat(data.populationCoverage()).isEqualTo(
                new RiskBackfillReadinessData.PopulationCoverage(1, 2, 1, 0));
        assertThat(data.horizonSnapshots().get(RiskHorizon.SHORT_TERM))
                .isEqualTo(new RiskBackfillReadinessData.HorizonSnapshotStats(3, 2, 1, 1));
        assertThat(data.horizonSnapshots().get(RiskHorizon.MEDIUM_TERM).formalMarketCount())
                .isEqualTo(1);
        assertThat(data.horizonSnapshots().get(RiskHorizon.LONG_TERM).formalMarketCount())
                .isEqualTo(1);
        assertThat(data.totalSnapshotCount()).isEqualTo(5);
        assertThat(data.formalSnapshotCount()).isEqualTo(4);
        assertThat(data.invalidFormalSnapshotCount()).isEqualTo(1);
        assertThat(data.timestampViolationCount()).isEqualTo(1);
        assertThat(data.enforcedGateCount()).isEqualTo(1);
        assertThat(data.checkpoints()).singleElement().satisfies(checkpoint -> {
            assertThat(checkpoint.datasetCode()).isEqualTo("market_daily");
            assertThat(checkpoint.qualityStatus()).isEqualTo("available");
        });
    }

    private void createSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE risk_object_exposure (
                    object_type VARCHAR(16), object_id VARCHAR(64),
                    parent_object_type VARCHAR(16), parent_object_id VARCHAR(64),
                    valid_from DATE, valid_to DATE, observed_at TIMESTAMP,
                    available_at TIMESTAMP, quality_status VARCHAR(32)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_indicator_observation (
                    object_type VARCHAR(16), object_id VARCHAR(64), trade_date DATE,
                    indicator_code VARCHAR(64), component_code VARCHAR(64),
                    indicator_value DECIMAL(30,10), observed_at TIMESTAMP,
                    available_at TIMESTAMP, source VARCHAR(64), quality_status VARCHAR(32)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_score_snapshot (
                    id BIGINT PRIMARY KEY, object_type VARCHAR(16), object_id VARCHAR(64),
                    horizon VARCHAR(16), trade_date DATE, completeness DECIMAL(6,5),
                    total_score DECIMAL(7,4), risk_level VARCHAR(16), risk_stage VARCHAR(16),
                    risk_confidence DECIMAL(6,5), model_version VARCHAR(64),
                    observed_at TIMESTAMP, available_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_event_fact (
                    id BIGINT PRIMARY KEY, observed_at TIMESTAMP, available_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_score_evidence (
                    id BIGINT PRIMARY KEY, observed_at TIMESTAMP, available_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_gate_result (
                    id BIGINT PRIMARY KEY, enforced BOOLEAN, observed_at TIMESTAMP,
                    available_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_ingestion_checkpoint (
                    provider_code VARCHAR(64), dataset_code VARCHAR(64), scope_key VARCHAR(128),
                    quality_status VARCHAR(32), last_error VARCHAR(1024),
                    observed_at TIMESTAMP, available_at TIMESTAMP
                )
                """);
    }

    private void insertExposure() {
        jdbcTemplate.update("""
                INSERT INTO risk_object_exposure VALUES
                ('stock', '600519.SH', 'sector', 'SW1:801780', ?, NULL, ?, ?, 'available')
                """, SCORE_START.minusYears(1), SCORE_START.atStartOfDay(), SCORE_START.atStartOfDay());
    }

    private void insertObservation(
            String objectType, String objectId, String code, String component,
            LocalDate tradeDate, String quality, String value,
            LocalDateTime observedAt, LocalDateTime availableAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO risk_indicator_observation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, objectType, objectId, tradeDate, code, component, value,
                observedAt, availableAt, "risk-derived-gateway", quality);
    }

    private void insertSnapshots() {
        insertSnapshot(1, "market", "CN-A", "1-5d", new java.math.BigDecimal("0.90"), true);
        insertSnapshot(2, "market", "CN-A", "5-20d", new java.math.BigDecimal("0.90"), true);
        insertSnapshot(3, "market", "CN-A", "20-60d", new java.math.BigDecimal("0.90"), true);
        insertSnapshot(4, "stock", "600519.SH", "1-5d", new java.math.BigDecimal("0.50"), false);
        insertSnapshot(5, "stock", "600519.SH", "1-5d", new java.math.BigDecimal("0.50"), true);
    }

    private void insertSnapshot(
            long id, String type, String objectId, String horizon,
            java.math.BigDecimal completeness, boolean conclusion
    ) {
        jdbcTemplate.update("""
                INSERT INTO risk_score_snapshot VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, type, objectId, horizon, END_DATE, completeness,
                conclusion ? "80" : null, conclusion ? "warning" : null,
                conclusion ? "repricing" : null, conclusion ? "0.9" : null,
                "risk-v1", END_DATE.atTime(19, 0), END_DATE.atTime(20, 0));
    }

    private void insertAuditRows() {
        jdbcTemplate.update("INSERT INTO risk_event_fact VALUES (1, ?, ?)",
                END_DATE.atTime(19, 0), END_DATE.atTime(18, 0));
        jdbcTemplate.update("INSERT INTO risk_score_evidence VALUES (1, ?, ?)",
                END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        jdbcTemplate.update("INSERT INTO risk_gate_result VALUES (1, true, ?, ?)",
                END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
        jdbcTemplate.update("""
                INSERT INTO risk_ingestion_checkpoint VALUES
                ('a-share-market-risk', 'market_daily', 'sample', 'available', NULL, ?, ?)
                """, END_DATE.atTime(18, 0), END_DATE.atTime(19, 0));
    }
}
