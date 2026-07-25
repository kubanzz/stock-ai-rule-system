package com.jx.tracker.risk.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

        LocalDate result = new JdbcRiskTradeDateResolver(jdbc).latestOpenDate(clock);

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 24));
    }
}
