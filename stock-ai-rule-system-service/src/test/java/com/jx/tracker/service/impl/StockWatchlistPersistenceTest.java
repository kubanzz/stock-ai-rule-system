package com.jx.tracker.service.impl;

import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.service.StockWatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.druid.master.url=jdbc:h2:mem:watchlist_persistence;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
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
class StockWatchlistPersistenceTest {

    @Autowired
    private StockWatchlistService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetSchema() {
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
                    group_name VARCHAR(128),
                    sort_order INT NOT NULL DEFAULT 0,
                    created_at DATETIME,
                    updated_at DATETIME,
                    CONSTRAINT uk_watchlist_symbol UNIQUE (watchlist_id, symbol),
                    CONSTRAINT fk_watchlist_item FOREIGN KEY (watchlist_id)
                        REFERENCES stock_watchlist(id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.update("""
                INSERT INTO stock_base(symbol, name, market, exchange, industry)
                VALUES ('600519.SH', '贵州茅台', 'A股', 'SH', '白酒')
                """);
    }

    @Test
    void createsAddsAndListsNormalizedStockThroughSpringProxy() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        StockConsoleVo.WatchlistPool pool = service.create(
                new StockConsoleVo.WatchlistMutationRequest("价值池", "A股"));

        service.addStock(pool.poolId(),
                new StockConsoleVo.WatchlistStockMutationRequest("sh600519", "核心"));

        assertThat(service.list("A股"))
                .filteredOn(candidate -> candidate.poolId().equals(pool.poolId()))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.stocks()).singleElement().satisfies(stock -> {
                    assertThat(stock.symbol()).isEqualTo("600519.SH");
                    assertThat(stock.groupName()).isEqualTo("核心");
                }));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT symbol FROM stock_watchlist_item WHERE watchlist_id = ?",
                String.class,
                jdbcTemplate.queryForObject("SELECT id FROM stock_watchlist WHERE pool_code = ?",
                        Long.class, pool.poolId())))
                .isEqualTo("600519.SH");
    }

    @Test
    void rejectsCrossMarketUpdateForNonEmptyPool() {
        StockConsoleVo.WatchlistPool pool = service.create(
                new StockConsoleVo.WatchlistMutationRequest("价值池", "A股"));
        service.addStock(pool.poolId(),
                new StockConsoleVo.WatchlistStockMutationRequest("600519.sh", null));

        assertThatThrownBy(() -> service.update(pool.poolId(),
                new StockConsoleVo.WatchlistMutationRequest("港股价值", "港股")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("非空股票池不可修改市场");
    }

    @Test
    void deletesCustomPoolAndCascadesItsMembers() {
        StockConsoleVo.WatchlistPool pool = service.create(
                new StockConsoleVo.WatchlistMutationRequest("价值池", "A股"));
        service.addStock(pool.poolId(),
                new StockConsoleVo.WatchlistStockMutationRequest("600519", null));

        service.delete(pool.poolId());

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_watchlist_item", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_watchlist WHERE pool_code = ?", Integer.class, pool.poolId()))
                .isZero();
    }

    @Test
    void refusesToDeleteDefaultSystemPoolRegardlessOfCodeCase() {
        service.list("港股");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT market FROM stock_watchlist WHERE pool_code = 'my-follow'", String.class))
                .isEqualTo("A股");
        assertThatThrownBy(() -> service.delete("MY-FOLLOW"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不可删除");
    }
}
