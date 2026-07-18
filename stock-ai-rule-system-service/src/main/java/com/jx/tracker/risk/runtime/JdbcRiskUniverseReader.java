package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.data.market.AshareRiskObjectCatalog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

public final class JdbcRiskUniverseReader implements RiskUniverseReader {

    private final JdbcTemplate jdbcTemplate;
    private final AshareRiskObjectCatalog objectCatalog = new AshareRiskObjectCatalog();

    public JdbcRiskUniverseReader(JdbcTemplate jdbcTemplate) {
        if (jdbcTemplate == null) {
            throw new IllegalArgumentException("jdbcTemplate must not be null");
        }
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<String> activeAshareSymbols() {
        return jdbcTemplate.queryForList("""
                SELECT symbol
                FROM stock_base
                WHERE LOWER(status) = 'active'
                  AND market IN ('CN', 'A股')
                ORDER BY symbol
                """, String.class).stream()
                .map(objectCatalog::stock)
                .map(object -> object.objectId())
                .distinct()
                .sorted()
                .toList();
    }
}
