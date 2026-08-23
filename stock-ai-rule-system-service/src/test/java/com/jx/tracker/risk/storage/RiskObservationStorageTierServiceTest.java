package com.jx.tracker.risk.storage;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.jx.tracker.risk.runtime.RiskStorageTierProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskObservationStorageTierServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-23T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    @Test
    void archivesOneBoundedExpiredBatchAfterBaselineAndColdCopyVerification() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(List.of(11L, 12L));
        RiskObservationStorageTierService service = new RiskObservationStorageTierService(
                jdbc, enabledProperties(), CLOCK);

        var result = service.archiveExpiredBatch();

        assertThat(result.cutoffDate()).isEqualTo(LocalDate.of(2024, 8, 23));
        assertThat(result.archivedRows()).isEqualTo(2);
        assertThat(jdbc.selectArgs).containsExactly(LocalDate.of(2024, 8, 23), 5_000);
        assertThat(jdbc.sql).hasSize(6);
        assertThat(jdbc.sql.get(1))
                .contains("INSERT INTO risk_indicator_baseline")
                .contains("ON DUPLICATE KEY UPDATE")
                .contains("actual_value = VALUES(actual_value)");
        assertThat(jdbc.sql.get(1)).doesNotContain("ROW_NUMBER()");
        assertThat(jdbc.sql.get(2)).contains("missing_baseline_rows");
        assertThat(jdbc.sql.get(3))
                .contains("INSERT INTO risk_indicator_observation_archive")
                .contains("indicator_value = VALUES(indicator_value)")
                .contains("payload_json = VALUES(payload_json)");
        assertThat(jdbc.sql.get(4))
                .contains("mismatched_archive_rows")
                .contains("archive.payload_json", "hot.payload_json");
        assertThat(jdbc.sql.get(5)).startsWith("DELETE FROM risk_indicator_observation");
        assertThat(jdbc.sql).allSatisfy(sql -> SQLUtils.parseSingleStatement(sql, DbType.mysql));
    }

    @Test
    void repeatedEmptyBatchIsIdempotentAndDoesNotWrite() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate(List.of());
        RiskObservationStorageTierService service = new RiskObservationStorageTierService(
                jdbc, enabledProperties(), CLOCK);

        var first = service.archiveExpiredBatch();
        var second = service.archiveExpiredBatch();

        assertThat(first.archivedRows()).isZero();
        assertThat(second.archivedRows()).isZero();
        assertThat(jdbc.sql).hasSize(2).allSatisfy(sql ->
                assertThat(sql).startsWith("SELECT id FROM risk_indicator_observation"));
    }

    @Test
    void refusesToArchiveBeforeTieredReadsAreExplicitlyEnabled() {
        RiskStorageTierProperties properties = enabledProperties();
        properties.setTieredReadEnabled(false);
        RiskObservationStorageTierService service = new RiskObservationStorageTierService(
                new RecordingJdbcTemplate(List.of()), properties, CLOCK);

        assertThatThrownBy(service::archiveExpiredBatch)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tiered-read-enabled");
    }

    private RiskStorageTierProperties enabledProperties() {
        RiskStorageTierProperties properties = new RiskStorageTierProperties();
        properties.setTieredReadEnabled(true);
        properties.setArchiveEnabled(true);
        return properties;
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<Long> ids;
        private final List<String> sql = new ArrayList<>();
        private final List<Object> selectArgs = new ArrayList<>();

        private RecordingJdbcTemplate(List<Long> ids) {
            this.ids = ids;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
            this.sql.add(sql);
            this.selectArgs.addAll(List.of(args));
            return (List<T>) ids;
        }

        @Override
        public int update(String sql, Object... args) {
            this.sql.add(sql);
            return sql.startsWith("DELETE") ? ids.size() : ids.size();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql.add(sql);
            int value = sql.contains("missing_baseline_rows")
                    || sql.contains("mismatched_archive_rows") ? 0 : ids.size();
            return (T) Integer.valueOf(value);
        }
    }
}
