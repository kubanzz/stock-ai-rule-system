package com.jx.tracker.risk.persistence.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskObjectExposureMapperTest {

    @Test
    void activeParentsOnlyReturnAvailableOrAuditedValidZeroExposures() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:risk_query_parent_quality;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE risk_object_exposure (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    object_type VARCHAR(16), object_id VARCHAR(64),
                    parent_object_type VARCHAR(16), parent_object_id VARCHAR(64),
                    exposure_weight DECIMAL(8, 6), valid_from DATE, valid_to DATE,
                    observed_at TIMESTAMP, available_at TIMESTAMP,
                    source VARCHAR(64), quality_status VARCHAR(32),
                    metadata_json VARCHAR(1024), created_at TIMESTAMP)
                """);
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        LocalDateTime asOf = tradeDate.atTime(20, 0);
        jdbc.update("""
                INSERT INTO risk_object_exposure (
                    object_type, object_id, parent_object_type, parent_object_id,
                    exposure_weight, valid_from, observed_at, available_at, source, quality_status)
                VALUES
                    ('stock', '600519.SH', 'sector', 'SW1:AVAILABLE', 0.40, ?, ?, ?, 'test', 'available'),
                    ('stock', '600519.SH', 'sector', 'SW1:VALID_ZERO', 0.30, ?, ?, ?, 'test', 'valid_zero'),
                    ('stock', '600519.SH', 'sector', 'SW1:STALE', 0.20, ?, ?, ?, 'test', 'stale'),
                    ('stock', '600519.SH', 'sector', 'SW1:UNAVAILABLE', 0.10, ?, ?, ?, 'test', 'unavailable')
                """,
                tradeDate.minusYears(1), tradeDate.atTime(15, 0), tradeDate.atTime(16, 0),
                tradeDate.minusYears(1), tradeDate.atTime(15, 0), tradeDate.atTime(16, 0),
                tradeDate.minusYears(1), tradeDate.atTime(15, 0), tradeDate.atTime(16, 0),
                tradeDate.minusYears(1), tradeDate.atTime(15, 0), tradeDate.atTime(16, 0));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment(
                "test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(RiskObjectExposureMapper.class);
        SqlSessionFactory sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);

        try (SqlSession session = sessionFactory.openSession()) {
            List<RiskObjectExposureEntity> parents = session.getMapper(RiskObjectExposureMapper.class)
                    .selectActiveParents("stock", "600519.SH", tradeDate, asOf);

            assertThat(parents).extracting(RiskObjectExposureEntity::getParentObjectId)
                    .containsExactly("SW1:AVAILABLE", "SW1:VALID_ZERO");
            assertThat(parents).extracting(RiskObjectExposureEntity::getQualityStatus)
                    .containsExactly("available", "valid_zero");
        }
    }
}
