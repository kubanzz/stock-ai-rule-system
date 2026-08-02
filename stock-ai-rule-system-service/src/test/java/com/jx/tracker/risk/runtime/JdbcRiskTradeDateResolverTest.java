package com.jx.tracker.risk.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRiskTradeDateResolverTest {

    @Test
    void returnsLatestOpenAshareDateNotAfterCurrentClockDate() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:risk_trade_date;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE trade_calendar (
                    market VARCHAR(16), trade_date DATE, is_open BOOLEAN)
                """);
        jdbc.execute("""
                INSERT INTO trade_calendar VALUES
                    ('CN', '2026-07-23', TRUE),
                    ('A股', '2026-07-24', TRUE),
                    ('CN', '2026-07-25', FALSE),
                    ('HK', '2026-07-25', TRUE),
                    ('CN', '2026-07-27', TRUE)
                """);
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-25T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

        LocalDate result = new JdbcRiskTradeDateResolver(
                jdbc, LocalTime.of(20, 0)).latestOpenDate(clock);

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 24));
    }

    @Test
    void tradingDayBeforeAvailabilityCutoffUsesPreviousCompletedOpenDate() {
        JdbcTemplate jdbc = calendarWithFridayAndMonday(
                "risk_trade_date_before_cutoff");
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-02T16:30:00Z"),
                ZoneId.of("Asia/Shanghai"));

        LocalDate result = new JdbcRiskTradeDateResolver(
                jdbc, LocalTime.of(20, 0)).latestOpenDate(clock);

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void tradingDayAtOrAfterAvailabilityCutoffCanUseCurrentOpenDate() {
        JdbcTemplate jdbc = calendarWithFridayAndMonday(
                "risk_trade_date_after_cutoff");
        Clock clock = Clock.fixed(
                Instant.parse("2026-08-03T12:00:00Z"),
                ZoneId.of("Asia/Shanghai"));

        LocalDate result = new JdbcRiskTradeDateResolver(
                jdbc, LocalTime.of(20, 0)).latestOpenDate(clock);

        assertThat(result).isEqualTo(LocalDate.of(2026, 8, 3));
    }

    private JdbcTemplate calendarWithFridayAndMonday(String databaseName) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:" + databaseName
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE trade_calendar (
                    market VARCHAR(16), trade_date DATE, is_open BOOLEAN)
                """);
        jdbc.execute("""
                INSERT INTO trade_calendar VALUES
                    ('CN', '2026-07-31', TRUE),
                    ('CN', '2026-08-01', FALSE),
                    ('CN', '2026-08-02', FALSE),
                    ('CN', '2026-08-03', TRUE)
                """);
        return jdbc;
    }
}
