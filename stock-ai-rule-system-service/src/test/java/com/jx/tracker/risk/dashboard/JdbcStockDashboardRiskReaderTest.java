package com.jx.tracker.risk.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.RiskGateStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.SignalDirection;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStockDashboardRiskReaderTest {

    @Test
    void readsLatestPointInTimeSnapshotEvidenceAndShadowGateByStableSignalReference() {
        JdbcTemplate jdbc = jdbc();
        createSchema(jdbc);
        LocalDate date = LocalDate.of(2026, 7, 10);
        LocalDateTime asOf = date.atTime(20, 0);
        jdbc.update("""
                INSERT INTO risk_score_snapshot VALUES
                (1, 'stock', '000004.SZ', '5-20d', ?, 70, 70, 70, 70, 70, 1.00, 70,
                 'warning', 'repricing', 0.85, 0.85, 'risk-v1', ?, ?, 'risk-engine', 'available', ?),
                (2, 'stock', '000004.SZ', '5-20d', ?, 80, 80, 80, 80, 80, 1.00, 80,
                 'critical', 'stampede', 0.90, 0.90, 'risk-v1', ?, ?, 'risk-engine', 'available', ?)
                """,
                date, date.atTime(17, 0), date.atTime(17, 0), date.atTime(17, 1),
                date, date.atTime(18, 0), date.atTime(18, 0), date.atTime(18, 1));
        jdbc.update("""
                INSERT INTO risk_score_evidence VALUES
                (1, 2, 'V', 'V1', 12.3, 95, 19, ?, ?, 'source-a', 'available', '{"sampleCount":1250}')
                """, date.atTime(17, 50), date.atTime(18, 0));
        String reference = RiskSignalCandidate.stockSignalReference("000004.SZ", date);
        jdbc.update("""
                INSERT INTO risk_gate_result VALUES
                (1, 2, ?, 'market', 'CN-A', '5-20d', ?, 'bullish', 0.80, 0.65,
                 'downgrade', 0, '市场 warning', 'risk-v1', ?, ?, 'risk-gate', 'available', ?)
                """, reference, date, date.atTime(18, 0), date.atTime(18, 0), date.atTime(18, 2));

        Map<String, StockDashboardRiskOverlay> result = new JdbcStockDashboardRiskReader(
                jdbc, new ObjectMapper()).findBySymbols(
                date, RiskHorizon.MEDIUM_TERM, List.of("000004.SZ"), asOf);

        assertThat(result).containsOnlyKeys("000004.SZ");
        StockDashboardRiskOverlay overlay = result.get("000004.SZ");
        assertThat(overlay.snapshot().level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(overlay.snapshot().evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.indicatorCode()).isEqualTo("V1");
            assertThat(evidence.details()).containsEntry("sampleCount", 1250);
        });
        assertThat(overlay.gateDecision().signalDirection()).isEqualTo(SignalDirection.BULLISH);
        assertThat(overlay.gateDecision().suggestedAction()).isEqualTo(RiskGateStatus.DOWNGRADE);
        assertThat(overlay.gateDecision().enforced()).isFalse();
    }

    private JdbcTemplate jdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:dashboard_risk_reader;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        return jdbc;
    }

    private void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE risk_score_snapshot (
                    id BIGINT PRIMARY KEY, object_type VARCHAR(16), object_id VARCHAR(64), horizon VARCHAR(16),
                    trade_date DATE, v_score DECIMAL, t_score DECIMAL, s_score DECIMAL, c_score DECIMAL,
                    a_score DECIMAL, m_score DECIMAL, total_score DECIMAL, risk_level VARCHAR(16),
                    risk_stage VARCHAR(16), completeness DECIMAL, risk_confidence DECIMAL,
                    model_version VARCHAR(64), observed_at TIMESTAMP, available_at TIMESTAMP,
                    source VARCHAR(64), quality_status VARCHAR(32), calculated_at TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE risk_score_evidence (
                    id BIGINT PRIMARY KEY, snapshot_id BIGINT, dimension_code CHAR(1), indicator_code VARCHAR(64),
                    raw_value DECIMAL, indicator_score DECIMAL, weighted_contribution DECIMAL,
                    observed_at TIMESTAMP, available_at TIMESTAMP, source VARCHAR(64),
                    quality_status VARCHAR(32), evidence_json VARCHAR(1024))
                """);
        jdbc.execute("""
                CREATE TABLE risk_gate_result (
                    id BIGINT PRIMARY KEY, snapshot_id BIGINT, signal_reference VARCHAR(64),
                    object_type VARCHAR(16), object_id VARCHAR(64), horizon VARCHAR(16), trade_date DATE,
                    signal_direction VARCHAR(16), original_confidence DECIMAL, suggested_confidence DECIMAL,
                    suggested_action VARCHAR(16), enforced BOOLEAN, reason VARCHAR(512), model_version VARCHAR(64),
                    observed_at TIMESTAMP, available_at TIMESTAMP, source VARCHAR(64),
                    quality_status VARCHAR(32), calculated_at TIMESTAMP)
                """);
    }
}
