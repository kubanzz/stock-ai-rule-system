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
        jdbcTemplate.execute("DROP TABLE IF EXISTS market_data_sync_run");
        jdbcTemplate.execute("DROP TABLE IF EXISTS trade_calendar");
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
                    `signal` VARCHAR(32),
                    signal_direction VARCHAR(16),
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
                CREATE TABLE trade_calendar (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    market VARCHAR(32) NOT NULL,
                    trade_date DATE NOT NULL,
                    is_open BOOLEAN NOT NULL,
                    pre_trade_date DATE,
                    next_trade_date DATE,
                    data_source VARCHAR(32),
                    sync_time DATETIME
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
                CREATE TABLE market_data_sync_run (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    data_source VARCHAR(64),
                    sync_type VARCHAR(64),
                    status VARCHAR(32),
                    request_params VARCHAR(512),
                    target_symbol VARCHAR(32),
                    start_date DATE,
                    end_date DATE,
                    trigger_type VARCHAR(32),
                    trigger_by VARCHAR(64),
                    scanned INT,
                    inserted INT,
                    updated INT,
                    skipped INT,
                    failed INT,
                    error_message VARCHAR(512),
                    started_at DATETIME,
                    finished_at DATETIME,
                    duration_ms BIGINT
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
                ('000005.SZ', '消费股份', 'CN', 'SZ', '消费'),
                ('000006.SZ', '医药股份', 'CN', 'SZ', '医药'),
                ('000007.SZ', '能源股份', 'CN', 'SZ', '能源'),
                ('000008.SZ', '金融股份', 'CN', 'SZ', '金融'),
                ('000009.SZ', '运输股份', 'CN', 'SZ', '运输'),
                ('00700.HK', '腾讯控股', 'HK', 'HK', '互联网'),
                ('AAPL.US', '苹果公司', 'US', 'NASDAQ', '科技')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_signal_daily(
                    symbol, signal_date, `signal`, bullish_score, bearish_score, risk_score,
                    confidence, triggered_rules, created_at
                ) VALUES
                ('000001.SZ', '2026-07-10', 'bullish', NULL, NULL, NULL, 0.80, 'R1,R2', '2026-07-10 15:00:00'),
                ('000001.SZ', '2026-07-09', 'bullish', 0.70, 0.10, 0.10, 0.82, 'R1', '2026-07-09 15:00:00'),
                ('000002.SZ', '2026-07-10', 'bearish', 0.10, 0.80, 0.20, 0.60, 'R3', '2026-07-10 15:00:00'),
                ('000006.SZ', '2026-07-10', NULL, 0.20, 0.20, 0.20, 0.70, 'R-NULL', '2026-07-10 15:00:00'),
                ('000007.SZ', '2026-07-10', 'unknown', 0.20, 0.20, 0.20, 0.70, 'R-UNKNOWN', '2026-07-10 15:00:00'),
                ('000003.SZ', '2026-07-12', 'bullish', 0.80, 0.10, 0.10, 0.84, 'R4', '2026-07-12 15:00:00'),
                ('000004.SZ', '2026-07-10', 'watch', NULL, NULL, NULL, NULL, NULL, '2026-07-10 14:00:00'),
                ('000005.SZ', '2026-07-10', 'high_risk', 0.20, 0.20, 0.90, 0.65, 'R6', '2026-07-10 15:00:00'),
                ('000001.SZ', '2026-07-08', 'bearish', 0.10, 0.80, 0.20, 0.70, 'R7', '2026-07-08 15:00:00'),
                ('000001.SZ', '2026-07-05', 'watch', 0.40, 0.30, 0.20, 0.60, 'R8', '2026-07-05 15:00:00'),
                ('000001.SZ', '2026-07-06', 'high_risk', 0.20, 0.20, 0.90, 0.65, 'R9', '2026-07-06 15:00:00'),
                ('000001.SZ', '2026-07-03', 'bullish', 0.80, 0.10, 0.10, 0.80, 'R10', '2026-07-03 15:00:00'),
                ('000001.SZ', '2026-07-02', 'bearish', 0.10, 0.80, 0.20, 0.70, 'R11', '2026-07-02 15:00:00'),
                ('000001.SZ', '2026-07-01', 'bullish', 0.80, 0.10, 0.10, 0.80, 'R12', '2026-07-01 15:00:00'),
                ('00700.HK', '2026-07-11', 'bullish', 0.90, 0.05, 0.05, 0.95, 'R5', '2026-07-11 15:00:00'),
                ('AAPL.US', '2026-07-11', 'watch', 0.50, 0.20, 0.10, 0.70, 'R13', '2026-07-11 15:00:00')
                """);
        jdbcTemplate.update("""
                INSERT INTO trade_calendar(market, trade_date, is_open, data_source) VALUES
                ('CN', '2026-07-10', TRUE, 'test'),
                ('CN', '2026-07-09', TRUE, 'test'),
                ('CN', '2026-07-08', TRUE, 'test'),
                ('CN', '2026-07-07', TRUE, 'test'),
                ('CN', '2026-07-06', TRUE, 'test'),
                ('CN', '2026-07-05', FALSE, 'test'),
                ('CN', '2026-07-04', FALSE, 'test'),
                ('CN', '2026-07-03', TRUE, 'test'),
                ('CN', '2026-07-02', TRUE, 'test'),
                ('CN', '2026-07-01', TRUE, 'test')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_daily_quote(symbol, trade_date, close_price, change_pct, sync_time)
                VALUES
                ('000001.SZ', '2026-07-10', 10.25, 1.50, '2026-07-10 15:10:00'),
                ('000002.SZ', '2026-07-10', 20.00, 4.00, '2026-07-10 15:10:00'),
                ('000003.SZ', '2026-07-10', 30.00, 2.50, '2026-07-10 15:10:00'),
                ('000004.SZ', '2026-07-10', 40.00, -1.00, '2026-07-10 15:10:00'),
                ('000005.SZ', '2026-07-10', 50.00, 3.00, '2026-07-10 15:10:00'),
                ('000006.SZ', '2026-07-10', 60.00, 2.00, '2026-07-10 15:10:00'),
                ('000007.SZ', '2026-07-10', 70.00, -2.00, '2026-07-10 15:10:00'),
                ('000008.SZ', '2026-07-10', 80.00, -3.00, '2026-07-10 15:10:00'),
                ('000009.SZ', '2026-07-10', 90.00, -4.00, '2026-07-10 15:10:00')
                """);
        for (int index = 0; index < 21; index++) {
            LocalDate tradeDate = DATE.minusDays(20L - index);
            jdbcTemplate.update("""
                            INSERT INTO stock_daily_quote(symbol, trade_date, close_price, change_pct, sync_time)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    "000300.SH", tradeDate, new BigDecimal("3000").add(BigDecimal.valueOf(index)),
                    index == 20 ? new BigDecimal("0.80") : new BigDecimal("0.10"),
                    tradeDate.atTime(15, 10));
        }
        jdbcTemplate.update("""
                INSERT INTO stock_daily_quote(symbol, trade_date, close_price, change_pct, sync_time)
                VALUES ('000300.SH', '2026-07-12', 4000.00, 2.00, '2026-07-12 15:10:00')
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
        jdbcTemplate.update("""
                INSERT INTO market_data_sync_run(
                    data_source, sync_type, status, target_symbol, end_date, started_at, finished_at
                )
                VALUES
                ('mock', 'daily_quote', 'success', NULL, '2026-07-09', '2026-07-10 14:00:00', '2026-07-10 14:05:00'),
                ('mock', 'daily_quote', 'success', '000005.SZ', '2026-07-10', '2026-07-10 15:00:00', '2026-07-10 15:05:00'),
                ('mock', 'daily_quote', 'failed', '000300.SH', '2026-07-10', '2026-07-10 16:00:00', '2026-07-10 16:01:00'),
                ('mock', 'daily_quote', 'running', NULL, '2026-07-11', '2026-07-10 17:00:00', NULL),
                ('mock', 'daily_quote', 'success', '00700.HK', '2026-07-10', '2026-07-10 18:00:00', '2026-07-10 18:01:00'),
                ('mock', 'stock_list', 'skipped', NULL, '2026-07-10', '2026-07-10 19:00:00', '2026-07-10 19:01:00'),
                ('mock', 'trade_calendar', 'success', NULL, '2026-07-10', '2026-07-10 20:00:00', '2026-07-10 20:01:00')
                """);
    }

    @Test
    void keepsWatchlistStocksVisibleAsPendingBeforeSignalsAreGenerated() {
        jdbcTemplate.update("""
                INSERT INTO stock_base(symbol, name, market, exchange, industry)
                VALUES ('600519.SH', '贵州茅台', 'CN', 'SH', '白酒')
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_watchlist_item(watchlist_id, symbol, sort_order)
                VALUES (7, '600519.SH', 2)
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_daily_quote(symbol, trade_date, close_price, change_pct, sync_time)
                VALUES ('600519.SH', '2026-07-10', 1500.00, 1.20, '2026-07-10 15:10:00')
                """);

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(
                new StockConsoleVo.SignalDashboardQuery(
                        DATE, "A股", "focus", null, null, null,
                        null, null, 1, 20, "symbol", "asc"));

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.signals()).filteredOn(row -> "600519.SH".equals(row.symbol()))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.signal()).isNull();
                    assertThat(row.signalStatus()).isEqualTo("pending");
                    assertThat(row.quoteStatus()).isEqualTo("ready");
                    assertThat(row.price()).isEqualByComparingTo("1500.00");
                    assertThat(row.triggeredRuleCount()).isZero();
                });
        assertThat(result.metrics()).filteredOn(metric -> "关注股票".equals(metric.label()))
                .singleElement()
                .extracting(StockConsoleVo.MetricCard::value)
                .isEqualTo(new BigDecimal("3"));
        assertThat(result.metrics()).filteredOn(metric -> "产生信号".equals(metric.label()))
                .singleElement()
                .extracting(StockConsoleVo.MetricCard::value)
                .isEqualTo(new BigDecimal("2"));
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

    @Test
    void aggregatesRealMarketContextUsingTwentyQuotesIndustryAveragesSevenEffectiveDatesAndLatestSyncRun() {
        StockConsoleVo.SignalDashboardOverview result = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", null, null, null,
                null, null, 1, 20, "symbol", "asc"
        ));

        StockConsoleVo.MarketContext context = result.marketContext();
        assertThat(context.available()).isTrue();
        assertThat(context.indexName()).isEqualTo("000300.SH");
        assertThat(context.indexValue()).isEqualByComparingTo("3020");
        assertThat(context.changePct()).isEqualByComparingTo("0.80");
        assertThat(context.trend()).hasSize(20);
        assertThat(context.trend().getFirst()).isEqualTo(
                new StockConsoleVo.SparkPoint("2026-06-21", new BigDecimal("3001.000000"))
        );
        assertThat(context.trend().getLast()).isEqualTo(
                new StockConsoleVo.SparkPoint("2026-07-10", new BigDecimal("3020.000000"))
        );

        assertThat(context.industryStrength())
                .extracting(StockConsoleVo.IndustryStrength::industry)
                .containsExactly("科技", "消费", "医药", "运输", "金融")
                .doesNotHaveDuplicates();
        assertThat(context.industryStrength())
                .extracting(StockConsoleVo.IndustryStrength::strength)
                .containsExactly(
                        new BigDecimal("4.00"), new BigDecimal("3.00"), new BigDecimal("2.00"),
                        new BigDecimal("-4.00"), new BigDecimal("-3.00")
                );

        // 最近 7 个开市日为 7/10、7/9、7/8、7/7、7/6、7/3、7/2，其中 7/7 无信号。
        // 7/5 闭市日信号及 null/unknown 均排除：bullish=3, bearish=3, watch=1, high_risk=2。
        // 指数 = (bullish*100 + watch*50 + high_risk*25 + bearish*0) / total = 44.44。
        assertThat(context.sentiment().label()).isEqualTo("信号情绪（7 日）·均衡");
        assertThat(context.sentiment().score()).isEqualByComparingTo("44.44");
        assertThat(context.sentiment().status()).isEqualTo("balanced");

        assertThat(context.riskOverview().highRiskCount()).isEqualTo(1);
        assertThat(context.riskOverview().highRiskRatio()).isEqualByComparingTo("25.00");
        assertThat(context.riskOverview().syncStatus()).isEqualTo("failed");
        assertThat(context.riskOverview().level()).isEqualTo("high");
        assertThat(context.riskOverview().summary()).contains("1", "25.00%", "failed");
    }

    @Test
    void appliesSignalAndConfidenceFiltersToSentimentAndSelectedDayRisk() {
        StockConsoleVo.SignalDashboardOverview highRisk = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", null, "high_risk", null,
                new BigDecimal("0.60"), new BigDecimal("0.70"), 1, 20, "symbol", "asc"
        ));
        StockConsoleVo.SignalDashboardOverview bullish = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", null, "bullish", null,
                new BigDecimal("0.81"), new BigDecimal("0.83"), 1, 20, "symbol", "asc"
        ));

        assertThat(highRisk.marketContext().sentiment().score()).isEqualByComparingTo("25.00");
        assertThat(highRisk.marketContext().riskOverview().highRiskCount()).isEqualTo(1);
        assertThat(highRisk.marketContext().riskOverview().highRiskRatio()).isEqualByComparingTo("100.00");
        assertThat(bullish.marketContext().sentiment().score()).isEqualByComparingTo("100.00");
        assertThat(bullish.marketContext().riskOverview().highRiskCount()).isZero();
        assertThat(bullish.marketContext().riskOverview().highRiskRatio()).isNull();
    }

    @Test
    void ignoresClosedDayAndInvalidSignalsWhileKeepingNoSignalTradingDayInWindow() {
        StockConsoleVo.MarketContext context = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", null, null, null,
                null, null, 1, 20, "symbol", "asc"
        )).marketContext();

        assertThat(context.sentiment().score()).isEqualByComparingTo("44.44");
        assertThat(context.riskOverview().highRiskCount()).isEqualTo(1);
        assertThat(context.riskOverview().highRiskRatio()).isEqualByComparingTo("25.00");
    }

    @Test
    void returnsUnavailableSentimentAndNoRiskSampleWhenTradingCalendarIsMissing() {
        jdbcTemplate.update("DELETE FROM trade_calendar");

        StockConsoleVo.MarketContext context = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", null, null, null,
                null, null, 1, 20, "symbol", "asc"
        )).marketContext();

        assertThat(context.sentiment().status()).isEqualTo("unavailable");
        assertThat(context.sentiment().score()).isNull();
        assertThat(context.riskOverview().highRiskCount()).isZero();
        assertThat(context.riskOverview().highRiskRatio()).isNull();
    }

    @Test
    void selectsLatestRelevantDailyQuoteSyncRunForDateAndTargets() {
        StockConsoleVo.RiskOverview risk = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", null, null, null,
                null, null, 1, 20, "symbol", "asc"
        )).marketContext().riskOverview();

        assertThat(risk.syncStatus()).isEqualTo("failed");
        assertThat(risk.summary()).contains("failed");
    }

    @Test
    void aggregatesBenchmarkAndRelevantSyncWhenCandidatesAreEmpty() {
        StockConsoleVo.MarketContext context = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                DATE, "CN", "all", "不存在", null, null,
                null, null, 1, 20, "symbol", "asc"
        )).marketContext();

        assertThat(context.available()).isTrue();
        assertThat(context.indexName()).isEqualTo("000300.SH");
        assertThat(context.indexValue()).isEqualByComparingTo("3020.00");
        assertThat(context.trend()).hasSize(20);
        assertThat(context.industryStrength()).isEmpty();
        assertThat(context.sentiment().status()).isEqualTo("unavailable");
        assertThat(context.riskOverview().highRiskRatio()).isNull();
        assertThat(context.riskOverview().syncStatus()).isEqualTo("failed");
    }

    @Test
    void usesLatestBenchmarkAndUnboundedRelevantSyncWhenDateAndCandidatesAreEmpty() {
        StockConsoleVo.MarketContext context = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                null, "CN", "all", "不存在", null, null,
                null, null, 1, 20, "symbol", "asc"
        )).marketContext();

        assertThat(context.available()).isTrue();
        assertThat(context.indexName()).isEqualTo("000300.SH");
        assertThat(context.indexValue()).isEqualByComparingTo("4000.00");
        assertThat(context.trend()).hasSize(20);
        assertThat(context.trend().getLast().label()).isEqualTo("2026-07-12");
        assertThat(context.riskOverview().syncStatus()).isEqualTo("running");
    }

    @Test
    void returnsUnavailableAndEmptyTrendWhenNormalizedMarketBenchmarkQuoteIsMissing() {
        jdbcTemplate.update("DELETE FROM market_data_sync_run");

        StockConsoleVo.SignalDashboardOverview result = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                LocalDate.of(2026, 7, 11), "HK", "all", null, null, null,
                null, null, 1, 20, "symbol", "asc"
        ));

        assertThat(result.marketContext().available()).isFalse();
        assertThat(result.marketContext().indexName()).isEqualTo("HSI.HK");
        assertThat(result.marketContext().indexValue()).isNull();
        assertThat(result.marketContext().trend()).isEmpty();
        assertThat(result.marketContext().riskOverview().syncStatus()).isEqualTo("unavailable");

        StockConsoleVo.SignalDashboardOverview usResult = service.dashboard(new StockConsoleVo.SignalDashboardQuery(
                LocalDate.of(2026, 7, 11), "US", "all", null, null, null,
                null, null, 1, 20, "symbol", "asc"
        ));
        assertThat(usResult.marketContext().available()).isFalse();
        assertThat(usResult.marketContext().indexName()).isEqualTo("SPX.US");
    }
}
