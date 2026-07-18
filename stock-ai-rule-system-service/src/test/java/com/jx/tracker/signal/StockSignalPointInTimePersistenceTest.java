package com.jx.tracker.signal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.signal.service.StockSignalService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class StockSignalPointInTimePersistenceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);

    private final AtomicInteger generation = new AtomicInteger();

    @Test
    void repeatedGenerationUpdatesTheFormalRowAndAppendsEveryAvailableVersion() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:signal_pit_service;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE stock_signal_daily (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    symbol VARCHAR(32), signal_date DATE, `signal` VARCHAR(32),
                    signal_direction VARCHAR(16), signal_level VARCHAR(32),
                    bullish_score DECIMAL(10, 4), bearish_score DECIMAL(10, 4),
                    risk_score DECIMAL(10, 4), confidence DECIMAL(10, 4),
                    triggered_rules VARCHAR(1024), explanation VARCHAR(1024),
                    risk_disclaimer VARCHAR(512), created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (symbol, signal_date)
                )
                """);
        jdbc.execute("""
                CREATE TABLE stock_signal_daily_history (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    signal_id BIGINT, symbol VARCHAR(32), signal_date DATE,
                    `signal` VARCHAR(32), signal_direction VARCHAR(16), signal_level VARCHAR(32),
                    bullish_score DECIMAL(10, 4), bearish_score DECIMAL(10, 4),
                    risk_score DECIMAL(10, 4), confidence DECIMAL(10, 4),
                    triggered_rules VARCHAR(1024), explanation VARCHAR(1024),
                    risk_disclaimer VARCHAR(512), available_at TIMESTAMP
                )
                """);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("h2", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(StockSignalDailyMapper.class);
        try (SqlSession sqlSession = new MybatisSqlSessionFactoryBuilder()
                .build(configuration)
                .openSession(true)) {
            StockSignalService service = new StockSignalService(
                    emptyRuleMapper(),
                    unusedFactorMapper(),
                    sqlSession.getMapper(StockSignalDailyMapper.class),
                    request -> request.symbol().equals("600519.SH") && generation.getAndIncrement() == 0
                            ? new RuleExecutionResult(new BigDecimal("75"), BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of())
                            : new RuleExecutionResult(BigDecimal.ZERO, new BigDecimal("80"), BigDecimal.ZERO, List.of(), List.of()),
                    new SignalScoringService()
            );

            service.generateDailySignal("600519.SH", DATE, java.util.Map.of());
            service.generateDailySignal("600519.SH", DATE, java.util.Map.of());
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM stock_signal_daily", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT signal_direction FROM stock_signal_daily WHERE symbol = '600519.SH'",
                String.class
        )).isEqualTo("bearish");
        assertThat(jdbc.queryForList(
                "SELECT signal_direction FROM stock_signal_daily_history ORDER BY id",
                String.class
        )).containsExactly("bullish", "bearish");
        assertThat(jdbc.queryForList(
                "SELECT available_at FROM stock_signal_daily_history ORDER BY id",
                Timestamp.class
        )).allSatisfy(availableAt -> assertThat(availableAt).isNotNull());
    }

    @SuppressWarnings("unchecked")
    private RuleDefinitionMapper emptyRuleMapper() {
        return (RuleDefinitionMapper) Proxy.newProxyInstance(
                RuleDefinitionMapper.class.getClassLoader(),
                new Class<?>[]{RuleDefinitionMapper.class},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return List.<RuleDefinition>of();
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private StockFactorDailyMapper unusedFactorMapper() {
        return (StockFactorDailyMapper) Proxy.newProxyInstance(
                StockFactorDailyMapper.class.getClassLoader(),
                new Class<?>[]{StockFactorDailyMapper.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
