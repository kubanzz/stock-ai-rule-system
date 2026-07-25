package com.jx.tracker.risk.runtime;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.LocalDate;

public final class JdbcRiskTradeDateResolver implements RiskTradeDateResolver {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskTradeDateResolver(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate is required");
        }
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LocalDate latestOpenDate(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        LocalDate latest = jdbcTemplate.queryForObject("""
                SELECT MAX(trade_date)
                FROM trade_calendar
                WHERE market IN ('CN', 'A股')
                  AND is_open = 1
                  AND trade_date <= ?
                """, LocalDate.class, LocalDate.now(clock));
        if (latest == null) {
            throw new IllegalStateException("未找到可用的 A 股交易日");
        }
        return latest;
    }
}
