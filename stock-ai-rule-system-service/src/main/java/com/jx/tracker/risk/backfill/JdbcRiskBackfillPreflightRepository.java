package com.jx.tracker.risk.backfill;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public final class JdbcRiskBackfillPreflightRepository implements RiskBackfillPreflightRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskBackfillPreflightRepository(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate must not be null");
        }
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<String> latestSuccessfulFlywayVersion() {
        List<String> versions = jdbcTemplate.queryForList("""
                SELECT version
                FROM flyway_schema_history
                WHERE success = 1 AND version IS NOT NULL
                ORDER BY installed_rank DESC
                LIMIT 1
                """, String.class);
        return versions.stream().findFirst();
    }

    @Override
    public boolean isOpenAshareTradingDay(LocalDate tradeDate) {
        if (tradeDate == null) {
            return false;
        }
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM trade_calendar
                WHERE market IN ('CN', 'A股', 'SSE', 'SZSE', 'SH', 'SZ')
                  AND trade_date = ? AND is_open = 1
                """, Long.class, tradeDate);
        return count != null && count > 0;
    }

    @Override
    public long enforcedGateCount() {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM risk_gate_result
                WHERE enforced <> 0
                """, Long.class);
        return count == null ? 0L : count;
    }
}
