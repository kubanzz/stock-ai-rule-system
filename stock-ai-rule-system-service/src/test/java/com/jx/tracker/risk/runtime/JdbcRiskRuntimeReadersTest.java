package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.SignalDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcRiskRuntimeReadersTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime AS_OF = DATE.atTime(20, 0);

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:risk_runtime_readers;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        ));
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("""
                CREATE TABLE stock_base (
                    symbol VARCHAR(32), market VARCHAR(32), status VARCHAR(32)
                )
                """);
        jdbc.execute("""
                CREATE TABLE stock_signal_daily (
                    id BIGINT, symbol VARCHAR(32), signal_date DATE,
                    signal_direction VARCHAR(16), confidence DECIMAL(8, 5), created_at TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE risk_object_exposure (
                    object_type VARCHAR(16), object_id VARCHAR(64),
                    parent_object_type VARCHAR(16), parent_object_id VARCHAR(64),
                    valid_from DATE, valid_to DATE, observed_at TIMESTAMP, available_at TIMESTAMP,
                    source VARCHAR(64), quality_status VARCHAR(32)
                )
                """);
    }

    @Test
    void universeReaderReturnsOnlyActiveAshareSymbolsInStableOrder() {
        jdbc.update("""
                INSERT INTO stock_base VALUES
                ('600519.SH', 'CN', 'active'),
                ('000001.SZ', 'A股', 'active'),
                ('000002.SZ', 'CN', 'inactive'),
                ('00700.HK', 'HK', 'active')
                """);

        List<String> symbols = new JdbcRiskUniverseReader(jdbc).activeAshareSymbols();

        assertThat(symbols).containsExactly("000001.SZ", "600519.SH");
    }

    @Test
    void candidateReaderUsesPersistedDirectionConfidenceAndPointInTimeExposure() {
        jdbc.update("""
                INSERT INTO stock_signal_daily VALUES
                (1, '600519.SH', ?, 'bullish', 0.82, ?),
                (2, '000001.SZ', ?, 'watch', 0.55, ?),
                (3, '600519.SH', ?, 'bearish', 0.91, ?)
                """,
                DATE, DATE.atTime(18, 0),
                DATE, DATE.atTime(18, 5),
                DATE, DATE.atTime(20, 1));
        jdbc.update("""
                INSERT INTO risk_object_exposure VALUES
                ('stock', '600519.SH', 'sector', 'SW1:801110', ?, ?, ?, ?, 'source-a', 'available'),
                ('stock', '600519.SH', 'sector', 'SW1:801120', ?, NULL, ?, ?, 'source-a', 'available'),
                ('stock', '600519.SH', 'sector', 'SW1:801130', ?, NULL, ?, ?, 'source-a', 'available'),
                ('stock', '600519.SH', 'sector', 'SW1:801140', ?, NULL, ?, ?, 'source-a', 'unavailable')
                """,
                DATE.minusYears(2), DATE.minusDays(1), DATE.minusYears(2).atStartOfDay(), DATE.minusYears(2).atTime(8, 0),
                DATE.minusYears(1), DATE.minusYears(1).atStartOfDay(), DATE.atTime(19, 0),
                DATE, DATE.atStartOfDay(), DATE.atTime(20, 1),
                DATE, DATE.atStartOfDay(), DATE.atTime(19, 30));
        List<RiskObjectKey> stocks = List.of(stock("000001.SZ"), stock("600519.SH"));

        List<RiskSignalCandidate> candidates = new JdbcRiskSignalCandidateReader(jdbc).read(
                DATE,
                stocks,
                List.of(RiskHorizon.values()),
                AS_OF
        );

        assertThat(candidates).hasSize(6);
        assertThat(candidates).filteredOn(candidate -> candidate.signalObject().equals(stock("600519.SH")))
                .allSatisfy(candidate -> {
                    assertThat(candidate.signalReference()).isEqualTo(
                            RiskSignalCandidate.stockSignalReference("600519.SH", DATE));
                    assertThat(candidate.direction()).isEqualTo(SignalDirection.BULLISH);
                    assertThat(candidate.originalConfidence()).isEqualByComparingTo(new BigDecimal("0.82"));
                    assertThat(candidate.relevantRiskObjects()).containsExactly(
                            market(), sector("SW1:801120"), stock("600519.SH"));
                });
        assertThat(candidates).filteredOn(candidate -> candidate.signalObject().equals(stock("000001.SZ")))
                .allSatisfy(candidate -> {
                    assertThat(candidate.direction()).isEqualTo(SignalDirection.WATCH);
                    assertThat(candidate.relevantRiskObjects()).containsExactly(
                            market(), stock("000001.SZ"));
                });
        assertThat(candidates).extracting(RiskSignalCandidate::horizon)
                .containsOnly(RiskHorizon.values());
    }

    private RiskObjectKey market() {
        return new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    }

    private RiskObjectKey sector(String id) {
        return new RiskObjectKey(RiskObjectType.SECTOR, id);
    }

    private RiskObjectKey stock(String id) {
        return new RiskObjectKey(RiskObjectType.STOCK, id);
    }
}
