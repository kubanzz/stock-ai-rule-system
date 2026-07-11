package com.jx.tracker.service.impl;

import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.service.StockDashboardQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.druid.master.url=jdbc:h2:mem:dashboard_persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.druid.master.username=sa",
        "spring.datasource.druid.master.password=",
        "spring.datasource.druid.master.driver-class-name=org.h2.Driver",
        "spring.datasource.druid.initialSize=0",
        "spring.datasource.druid.minIdle=0",
        "spring.datasource.druid.maxActive=2",
        "spring.datasource.druid.validationQuery=SELECT 1",
        "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("test")
class StockDashboardQueryPersistenceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 10);

    @Autowired
    private StockDashboardQueryService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS stock_actual_result");
        jdbcTemplate.execute("DROP TABLE IF EXISTS stock_daily_quote");
        jdbcTemplate.execute("DROP TABLE IF EXISTS stock_signal_daily");
        jdbcTemplate.execute("DROP TABLE IF EXISTS stock_watchlist_item");
        jdbcTemplate.execute("DROP TABLE IF EXISTS stock_watchlist");
        jdbcTemplate.execute("DROP TABLE IF EXISTS stock_base");
        jdbcTemplate.execute("""
                CREATE TABLE stock_base (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol VARCHAR(32) NOT NULL UNIQUE,
                    name VARCHAR(128) NOT NULL,
                    market VARCHAR(32) NOT NULL,
                    exchange VARCHAR(16),
                    industry VARCHAR(128),
                    status VARCHAR(16),
                    data_source VARCHAR(32),
                    last_sync_time DATETIME,
                    created_at DATETIME,
                    updated_at DATETIME
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE stock_signal_daily (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol VARCHAR(32) NOT NULL,
                    signal_date DATE NOT NULL,
                    `signal` VARCHAR(32) NOT NULL,
                    signal_level VARCHAR(32),
                    bullish_score DECIMAL(18,6),
                    bearish_score DECIMAL(18,6),
                    risk_score DECIMAL(18,6),
                    confidence DECIMAL(18,6),
                    triggered_rules VARCHAR(512),
                    explanation VARCHAR(512),
                    risk_disclaimer VARCHAR(512),
                    created_at DATETIME
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE stock_daily_quote (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol VARCHAR(32) NOT NULL,
                    trade_date DATE NOT NULL,
                    open_price DECIMAL(18,6),
                    high_price DECIMAL(18,6),
                    low_price DECIMAL(18,6),
                    close_price DECIMAL(18,6),
                    pre_close DECIMAL(18,6),
                    volume DECIMAL(24,6),
                    amount DECIMAL(24,6),
                    change_pct DECIMAL(18,6),
                    data_source VARCHAR(32),
                    sync_time DATETIME,
                    created_at DATETIME
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE stock_actual_result (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol VARCHAR(32) NOT NULL,
                    signal_date DATE NOT NULL,
                    return1d DECIMAL(18,6),
                    return3d DECIMAL(18,6),
                    return5d DECIMAL(18,6),
                    return10d DECIMAL(18,6),
                    hit1d BOOLEAN,
                    hit5d BOOLEAN,
                    created_at DATETIME
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE stock_watchlist (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    pool_code VARCHAR(64) NOT NULL UNIQUE,
                    pool_name VARCHAR(128) NOT NULL,
                    market VARCHAR(32) NOT NULL,
                    sort_order INT NOT NULL DEFAULT 0,
                    is_system BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at DATETIME,
                    updated_at DATETIME
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE stock_watchlist_item (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    watchlist_id BIGINT NOT NULL,
                    symbol VARCHAR(32) NOT NULL,
                    group_name VARCHAR(64),
                    sort_order INT NOT NULL DEFAULT 0,
                    created_at DATETIME,
                    updated_at DATETIME
                )
                """);

        jdbcTemplate.update("""
                INSERT INTO stock_base(symbol, name, market, exchange, industry) VALUES
                ('000001.SZ', '平安银行', 'CN', 'SZ', '银行'),
                ('000002.SZ', '科技股份', 'CN', 'SZ', '科技'),
                ('000003.SZ', '池外股份', 'CN', 'SZ', '银行'),
                ('000004.SZ', '空值股份', 'CN', 'SZ', '银行'),
                ('00700.HK', '腾讯控股', 'HK', 'HK', '互联网')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_signal_daily(
                    symbol, signal_date, `signal`, bullish_score, bearish_score, risk_score,
                    confidence, triggered_rules, created_at
                ) VALUES
                ('000001.SZ', '2026-07-10', 'bullish', NULL, NULL, NULL, 0.80, 'R1,R2', '2026-07-10 15:00:00'),
                ('000001.SZ', '2026-07-09', 'bullish', 0.70, 0.10, 0.10, 0.82, 'R1', '2026-07-09 15:00:00'),
                ('000002.SZ', '2026-07-10', 'bearish', 0.10, 0.80, 0.20, 0.60, 'R3', '2026-07-10 15:00:00'),
                ('000003.SZ', '2026-07-12', 'bullish', 0.80, 0.10, 0.10, 0.84, 'R4', '2026-07-12 15:00:00'),
                ('000004.SZ', '2026-07-10', 'watch', NULL, NULL, NULL, NULL, NULL, '2026-07-10 14:00:00'),
                ('00700.HK', '2026-07-11', 'bullish', 0.90, 0.05, 0.05, 0.95, 'R5', '2026-07-11 15:00:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_daily_quote(symbol, trade_date, close_price, change_pct, sync_time)
                VALUES ('000001.SZ', '2026-07-10', 10.25, 1.50, '2026-07-10 15:10:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_actual_result(symbol, signal_date, hit5d, created_at)
                VALUES ('000001.SZ', '2026-07-10', TRUE, '2026-07-10 15:20:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_watchlist(id, pool_code, pool_name, market, sort_order, is_system)
                VALUES (7, 'focus', '关注池', 'CN', 10, FALSE)
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_watchlist_item(watchlist_id, symbol, sort_order) VALUES
                (7, '000001.SZ', 0),
                (7, '000002.SZ', 1)
                """);
    }

    @Test
    void appliesRealAliasPoolCandidateDateSignalConfidenceQuoteAndActualQueries() {
        StockConsoleVo.SignalDashboardOverview result = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                null, "A股", " FOCUS ", null, "bullish", "银行",
                new BigDecimal("0.70"), new BigDecimal("0.85"),
                1, 20, "confidence", "desc"
        ));

        assertThat(result.tradeDate()).isEqualTo(DATE);
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.signals()).singleElement().satisfies(row -> {
            assertThat(row.symbol()).isEqualTo("000001.SZ");
            assertThat(row.price()).isEqualByComparingTo("10.25");
            assertThat(row.changePct()).isEqualByComparingTo("1.50");
            assertThat(row.bullishScore()).isNull();
            assertThat(row.bearishScore()).isNull();
            assertThat(row.riskScore()).isNull();
            assertThat(row.confidence()).isEqualByComparingTo("0.80");
        });
        assertThat(result.metrics()).filteredOn(metric -> "命中率（5日）".equals(metric.label()))
                .singleElement()
                .extracting(StockConsoleVo.MetricCard::value)
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(result.dataUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 10, 15, 20));
    }

    @Test
    void preservesNullConfidenceWithoutConfidenceFilter() {
        StockConsoleVo.SignalDashboardOverview result = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", "000004", "watch", "银行",
                null, null, 1, 20, "symbol", "asc"
        ));

        assertThat(result.signals()).singleElement().satisfies(row -> {
            assertThat(row.bullishScore()).isNull();
            assertThat(row.bearishScore()).isNull();
            assertThat(row.riskScore()).isNull();
            assertThat(row.confidence()).isNull();
        });
    }
}
