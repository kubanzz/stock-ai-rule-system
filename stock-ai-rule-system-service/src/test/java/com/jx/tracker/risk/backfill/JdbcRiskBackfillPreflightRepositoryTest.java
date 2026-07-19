package com.jx.tracker.risk.backfill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRiskBackfillPreflightRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcRiskBackfillPreflightRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_backfill_preflight;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP ALL OBJECTS");
        jdbcTemplate.execute("""
                CREATE TABLE flyway_schema_history (
                    installed_rank INT PRIMARY KEY,
                    version VARCHAR(50),
                    success BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE trade_calendar (
                    market VARCHAR(32) NOT NULL,
                    trade_date DATE NOT NULL,
                    is_open BOOLEAN NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE risk_gate_result (
                    id BIGINT PRIMARY KEY,
                    enforced BOOLEAN NOT NULL
                )
                """);
        repository = new JdbcRiskBackfillPreflightRepository(jdbcTemplate);
    }

    @Test
    void readsLatestSuccessfulFlywayVersionOnly() {
        jdbcTemplate.update("INSERT INTO flyway_schema_history VALUES (1, '1', true)");
        jdbcTemplate.update("INSERT INTO flyway_schema_history VALUES (2, '2', true)");
        jdbcTemplate.update("INSERT INTO flyway_schema_history VALUES (3, '3', false)");

        assertThat(repository.latestSuccessfulFlywayVersion()).contains("2");
    }

    @Test
    void recognizesAnOpenAshareTradingDate() {
        LocalDate date = LocalDate.of(2026, 7, 10);
        jdbcTemplate.update("INSERT INTO trade_calendar VALUES ('SSE', ?, true)", date);
        jdbcTemplate.update("INSERT INTO trade_calendar VALUES ('HK', ?, true)", date);

        assertThat(repository.isOpenAshareTradingDay(date)).isTrue();
        assertThat(repository.isOpenAshareTradingDay(date.minusDays(1))).isFalse();
    }

    @Test
    void countsAnyNonShadowGateRows() {
        jdbcTemplate.update("INSERT INTO risk_gate_result VALUES (1, false)");
        jdbcTemplate.update("INSERT INTO risk_gate_result VALUES (2, true)");

        assertThat(repository.enforcedGateCount()).isEqualTo(1L);
    }
}
