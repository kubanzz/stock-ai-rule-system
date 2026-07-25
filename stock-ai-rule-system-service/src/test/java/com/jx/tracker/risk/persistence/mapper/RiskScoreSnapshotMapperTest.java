package com.jx.tracker.risk.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskScoreSnapshotMapperTest {

    @Test
    void levelFilterCountsAndPagesOnlyFormalSnapshotsWhileAuditListKeepsAllQualities() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_query_level_quality;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE stock_base (
                    symbol VARCHAR(32) PRIMARY KEY,
                    name VARCHAR(128))
                """);
        jdbc.execute("""
                CREATE TABLE risk_score_snapshot (
                    id BIGINT PRIMARY KEY,
                    object_type VARCHAR(16), object_id VARCHAR(64),
                    horizon VARCHAR(16), trade_date DATE,
                    total_score DECIMAL(8, 4), risk_level VARCHAR(16),
                    completeness DECIMAL(6, 4), quality_status VARCHAR(32),
                    calculated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE risk_object_exposure (
                    object_type VARCHAR(16), object_id VARCHAR(64),
                    parent_object_type VARCHAR(16), parent_object_id VARCHAR(64),
                    parent_object_name VARCHAR(128), available_at TIMESTAMP)
                """);
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        jdbc.update("""
                INSERT INTO risk_score_snapshot (
                    id, object_type, object_id, horizon, trade_date, total_score,
                    risk_level, completeness, quality_status, calculated_at)
                VALUES
                    (1, 'stock', '600519.SH', '1-5d', ?, 98, 'critical', 0.95, 'stale',
                     '2026-07-18 16:00:00'),
                    (2, 'stock', '000001.SZ', '1-5d', ?, 90, 'critical', 0.79, 'available',
                     '2026-07-18 16:00:00'),
                    (3, 'stock', '000333.SZ', '1-5d', ?, 85, 'critical', 0.80, 'available',
                     '2026-07-18 16:00:00')
                """, tradeDate, tradeDate, tradeDate);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(RiskScoreSnapshotMapper.class);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            RiskScoreSnapshotMapper mapper = session.getMapper(RiskScoreSnapshotMapper.class);

            long formalCount = mapper.countObjectPage(
                    "stock", "critical", "1-5d", tradeDate, null, null, null);
            List<RiskScoreSnapshotEntity> formalRows = mapper.selectObjectPage(
                    "stock", "critical", "1-5d", tradeDate, null,
                    null, null, 0L, 20);
            long auditCount = mapper.countObjectPage(
                    "stock", null, "1-5d", tradeDate, null, null, null);
            List<RiskScoreSnapshotEntity> auditRows = mapper.selectObjectPage(
                    "stock", null, "1-5d", tradeDate, null,
                    null, null, 0L, 20);

            assertThat(formalCount).isEqualTo(1);
            assertThat(formalRows).extracting(RiskScoreSnapshotEntity::getObjectId)
                    .containsExactly("000333.SZ");
            assertThat(auditCount).isEqualTo(3);
            assertThat(auditRows).extracting(RiskScoreSnapshotEntity::getQualityStatus)
                    .containsExactly("stale", "available", "available");
        }
    }

    @Test
    void sectorRowsUseTheLatestPersistedIndustryName() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_query_sector_name;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE stock_base (symbol VARCHAR(32) PRIMARY KEY, name VARCHAR(128))");
        jdbc.execute("""
                CREATE TABLE risk_score_snapshot (
                    id BIGINT PRIMARY KEY, object_type VARCHAR(16), object_id VARCHAR(64),
                    horizon VARCHAR(16), trade_date DATE, total_score DECIMAL(8, 4),
                    risk_level VARCHAR(16), completeness DECIMAL(6, 4),
                    quality_status VARCHAR(32), calculated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE risk_object_exposure (
                    object_type VARCHAR(16), object_id VARCHAR(64),
                    parent_object_type VARCHAR(16), parent_object_id VARCHAR(64),
                    parent_object_name VARCHAR(128), available_at TIMESTAMP)
                """);
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        jdbc.update("""
                INSERT INTO risk_score_snapshot VALUES
                (1, 'sector', 'SW1:801780', '1-5d', ?, 42, NULL, 0.45,
                 'insufficient_history', '2026-07-18 16:00:00')
                """, tradeDate);
        jdbc.execute("""
                INSERT INTO risk_object_exposure VALUES
                ('stock', '600519.SH', 'sector', 'SW1:801780', '银行', '2026-07-18 15:00:00')
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(RiskScoreSnapshotMapper.class);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            List<RiskScoreSnapshotEntity> rows = session.getMapper(RiskScoreSnapshotMapper.class)
                    .selectForOverview("1-5d", tradeDate);

            assertThat(rows).singleElement()
                    .extracting(RiskScoreSnapshotEntity::getObjectName)
                    .isEqualTo("银行");
        }
    }
}
