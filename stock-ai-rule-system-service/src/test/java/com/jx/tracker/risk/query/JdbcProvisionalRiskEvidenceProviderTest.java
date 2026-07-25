package com.jx.tracker.risk.query;

import com.jx.tracker.risk.persistence.entity.RiskProvisionalObservationRow;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.persistence.mapper.RiskObjectExposureMapper;
import com.jx.tracker.risk.persistence.mapper.RiskProvisionalObservationMapper;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcProvisionalRiskEvidenceProviderTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 24);
    private static final LocalDateTime AS_OF = TRADE_DATE.atTime(20, 0);

    @Test
    void normalizesPartialHistoryAndKeepsItQueryOnly() {
        RiskProvisionalObservationMapper mapper = mock(RiskProvisionalObservationMapper.class);
        RiskObjectExposureMapper exposureMapper = mock(RiskObjectExposureMapper.class);
        when(mapper.selectHistory(
                "market", "CN-A", "1-5d", TRADE_DATE.minusYears(6), TRADE_DATE, AS_OF
        )).thenReturn(history("market", "CN-A", "V3", "relativeReturn", 25));
        when(mapper.selectSectorCrossSection("1-5d", TRADE_DATE, AS_OF))
                .thenReturn(List.of());
        JdbcProvisionalRiskEvidenceProvider provider =
                new JdbcProvisionalRiskEvidenceProvider(mapper, exposureMapper);

        RiskScoreSnapshotEntity snapshot = snapshot(1L, "market", "CN-A");
        List<RiskEvidence> evidence = provider.load(List.of(snapshot)).get(1L);

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.indicatorCode()).isEqualTo("V3");
            assertThat(item.score()).isEqualByComparingTo("100.0000");
            assertThat(item.qualityStatus()).isEqualTo("insufficient_history");
            assertThat(item.details())
                    .containsEntry("provisionalOnly", true)
                    .containsEntry("normalization", "partial_rolling_percentile")
                    .containsEntry("sampleCount", 25);
        });
    }

    @Test
    void usesCurrentSectorCrossSectionWhenOwnHistoryIsTooShort() {
        RiskProvisionalObservationMapper mapper = mock(RiskProvisionalObservationMapper.class);
        RiskObjectExposureMapper exposureMapper = mock(RiskObjectExposureMapper.class);
        RiskProvisionalObservationRow target = row(
                "sector", "SW1:801120", TRADE_DATE, "V3", "relativeReturn", "20");
        when(mapper.selectHistory(
                "sector", "SW1:801120", "1-5d", TRADE_DATE.minusYears(6), TRADE_DATE, AS_OF
        )).thenReturn(List.of(target));
        List<RiskProvisionalObservationRow> crossSection = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            crossSection.add(row(
                    "sector", "SW1:" + String.format("%06d", 801000 + index),
                    TRADE_DATE, "V3", "relativeReturn", String.valueOf(index)));
        }
        when(mapper.selectSectorCrossSection("1-5d", TRADE_DATE, AS_OF))
                .thenReturn(crossSection);
        JdbcProvisionalRiskEvidenceProvider provider =
                new JdbcProvisionalRiskEvidenceProvider(mapper, exposureMapper);

        RiskScoreSnapshotEntity snapshot = snapshot(2L, "sector", "SW1:801120");
        List<RiskEvidence> evidence = provider.load(List.of(snapshot)).get(2L);

        assertThat(evidence).singleElement().satisfies(item -> {
            assertThat(item.score()).isEqualByComparingTo("100.0000");
            assertThat(item.details())
                    .containsEntry("normalization", "current_cross_section_percentile")
                    .containsEntry("sampleCount", 20);
        });
    }

    private List<RiskProvisionalObservationRow> history(
            String objectType,
            String objectId,
            String indicator,
            String component,
            int size
    ) {
        List<RiskProvisionalObservationRow> rows = new ArrayList<>();
        for (int index = 1; index <= size; index++) {
            rows.add(row(
                    objectType, objectId, TRADE_DATE.minusDays(size - index),
                    indicator, component, String.valueOf(index)
            ));
        }
        return rows;
    }

    private RiskProvisionalObservationRow row(
            String objectType,
            String objectId,
            LocalDate tradeDate,
            String indicator,
            String component,
            String value
    ) {
        RiskProvisionalObservationRow row = new RiskProvisionalObservationRow();
        row.setObjectType(objectType);
        row.setObjectId(objectId);
        row.setHorizon("1-5d");
        row.setTradeDate(tradeDate);
        row.setDimensionCode(indicator.substring(0, 1));
        row.setIndicatorCode(indicator);
        row.setComponentCode(component);
        row.setActualValue(new BigDecimal(value));
        row.setObservedAt(tradeDate.atTime(15, 0));
        row.setAvailableAt(tradeDate.atTime(16, 0));
        row.setSource("fixture");
        row.setQualityStatus("insufficient_history");
        return row;
    }

    private RiskScoreSnapshotEntity snapshot(long id, String objectType, String objectId) {
        RiskScoreSnapshotEntity snapshot = new RiskScoreSnapshotEntity();
        snapshot.setId(id);
        snapshot.setObjectType(objectType);
        snapshot.setObjectId(objectId);
        snapshot.setHorizon("1-5d");
        snapshot.setTradeDate(TRADE_DATE);
        snapshot.setCalculatedAt(AS_OF);
        return snapshot;
    }
}
