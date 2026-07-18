package com.jx.tracker.risk.query;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreEvidenceEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.persistence.mapper.RiskObjectExposureMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreEvidenceMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreSnapshotMapper;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectListItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RiskAssessmentQueryServiceImplTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime CALCULATED_AT = LocalDateTime.of(2026, 7, 18, 16, 0);

    private RiskScoreSnapshotMapper snapshotMapper;
    private RiskScoreEvidenceMapper evidenceMapper;
    private RiskObjectExposureMapper exposureMapper;
    private RiskAssessmentQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        snapshotMapper = mock(RiskScoreSnapshotMapper.class);
        evidenceMapper = mock(RiskScoreEvidenceMapper.class);
        exposureMapper = mock(RiskObjectExposureMapper.class);
        service = new RiskAssessmentQueryServiceImpl(snapshotMapper, evidenceMapper, exposureMapper);
    }

    @Test
    void detailUsesSelectedSnapshotContractAndParentPointInTime() {
        RiskScoreSnapshotEntity incomplete = snapshot(1L, "1-5d", "0.79", "available", "warning");
        RiskScoreSnapshotEntity medium = snapshot(2L, "5-20d", "0.95", "available", "critical");
        RiskScoreSnapshotEntity longTerm = snapshot(3L, "20-60d", "0.90", "stale", "watch");
        incomplete.setObjectName("贵州茅台");
        when(snapshotMapper.selectLatestTradeDateForObject("stock", "600519.SH", null))
                .thenReturn(TRADE_DATE);
        when(snapshotMapper.selectForObject("stock", "600519.SH", null, TRADE_DATE))
                .thenReturn(List.of(incomplete, medium, longTerm));

        RiskScoreEvidenceEntity unavailable = evidence(11L, 1L, "C", "price_confirmation", "unavailable");
        RiskScoreEvidenceEntity activeTrigger = evidence(12L, 1L, "T", "credit_event", "available");
        when(evidenceMapper.selectBySnapshotIds(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(unavailable, activeTrigger));
        when(exposureMapper.selectActiveParents("stock", "600519.SH", TRADE_DATE, CALCULATED_AT))
                .thenReturn(List.of(exposure("sector", "BK0475", "stale")));

        RiskObjectDetail detail = service.objectDetail("stock", "600519.SH", null, null);

        assertThat(detail.name()).isEqualTo("贵州茅台");
        assertThat(detail.object().objectType()).isEqualTo("stock");
        assertThat(detail.snapshot().horizon()).isEqualTo("1-5d");
        assertThat(detail.snapshot().totalScore()).isNull();
        assertThat(detail.snapshot().level()).isNull();
        assertThat(detail.snapshot().stage()).isNull();
        assertThat(detail.snapshot().riskConfidence()).isNull();
        assertThat(detail.snapshot().evidence().getFirst().qualityStatus()).isEqualTo("unavailable");
        assertThat(detail.snapshot().evidence().getFirst().rawValue()).isNull();
        assertThat(detail.snapshot().evidence().getFirst().score()).isNull();
        assertThat(detail.snapshots()).extracting("horizon")
                .containsExactly("1-5d", "5-20d", "20-60d");
        assertThat(detail.activeTriggers()).containsExactly("credit_event");
        assertThat(detail.parentObjects()).singleElement().satisfies(parent -> {
            assertThat(parent.objectType()).isEqualTo("sector");
            assertThat(parent.objectId()).isEqualTo("BK0475");
        });
        verify(exposureMapper).selectActiveParents("stock", "600519.SH", TRADE_DATE, CALCULATED_AT);
    }

    @Test
    void emptyDetailIsNotRepresentedAsInventedZeroSnapshot() {
        when(snapshotMapper.selectLatestTradeDateForObject("market", "CN-A", null)).thenReturn(null);

        assertThatThrownBy(() -> service.objectDetail("market", "CN-A", null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未找到风险对象快照");
    }

    @Test
    void overviewCountsOnlyRequestedHorizonAndUsesOneTradeDate() {
        RiskScoreSnapshotEntity market = snapshot(1L, "1-5d", "0.90", "available", "normal", "market", "CN-A");
        RiskScoreSnapshotEntity shortTerm = snapshot(2L, "1-5d", "0.90", "available", "warning");
        RiskScoreSnapshotEntity otherHorizon = snapshot(3L, "5-20d", "0.90", "available", "critical");
        when(snapshotMapper.selectLatestTradeDate("1-5d")).thenReturn(TRADE_DATE);
        when(snapshotMapper.selectForOverview("1-5d", TRADE_DATE))
                .thenReturn(List.of(market, shortTerm, otherHorizon));

        var overview = service.overview(null, null);

        assertThat(overview.horizon()).isEqualTo("1-5d");
        assertThat(overview.tradeDate()).isEqualTo(TRADE_DATE);
        assertThat(overview.levelCounts()).extracting("level", "count")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("normal", 1L),
                        org.assertj.core.groups.Tuple.tuple("watch", 0L),
                        org.assertj.core.groups.Tuple.tuple("warning", 1L),
                        org.assertj.core.groups.Tuple.tuple("critical", 0L)
                );
        assertThat(overview.marketSnapshot()).isNotNull();
        assertThat(overview.highRiskObjects()).isEmpty();
        assertThat(overview.riskDisclaimer()).isEqualTo(RiskDecisionSupportNotice.TEXT);
    }

    @Test
    void overviewWithoutAnyAvailableTradeDateDoesNotBreakNonNullFrontendContract() {
        when(snapshotMapper.selectLatestTradeDate("1-5d")).thenReturn(null);

        assertThatThrownBy(() -> service.overview(null, null))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("未找到风险快照");
    }

    @Test
    void objectFiltersAndPaginationAreDelegatedToDatabaseForOneHorizonAndDate() {
        RiskScoreSnapshotEntity medium = snapshot(
                2L, "5-20d", "0.90", "available", "warning", "stock", "600519.SH"
        );
        medium.setObjectName("贵州茅台");
        when(snapshotMapper.selectLatestTradeDate("5-20d")).thenReturn(TRADE_DATE);
        when(snapshotMapper.countObjectPage("stock", "warning", "5-20d", TRADE_DATE, "茅台"))
                .thenReturn(2L);
        when(snapshotMapper.selectObjectPage(
                "stock", "warning", "5-20d", TRADE_DATE, "茅台", 1L, 1
        )).thenReturn(List.of(medium));

        PageResult<RiskObjectListItem> page = service.listObjects(
                "stock", "warning", "5-20d", null, " 茅台 ", 2, 1
        );

        assertThat(page.getCode()).isEqualTo(200);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRows()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("贵州茅台");
            assertThat(item.object().objectId()).isEqualTo("600519.SH");
            assertThat(item.snapshot().horizon()).isEqualTo("5-20d");
            assertThat(item.snapshot().level()).isEqualTo("warning");
        });
        verify(snapshotMapper).selectObjectPage(
                "stock", "warning", "5-20d", TRADE_DATE, "茅台", 1L, 1
        );

        long distantOffset = (long) (Integer.MAX_VALUE - 1) * 100;
        when(snapshotMapper.selectObjectPage(
                "stock", "warning", "5-20d", TRADE_DATE, null, distantOffset, 100
        )).thenReturn(List.of());
        when(snapshotMapper.countObjectPage("stock", "warning", "5-20d", TRADE_DATE, null))
                .thenReturn(2L);
        assertThat(service.listObjects(
                "stock", "warning", "5-20d", TRADE_DATE, null, Integer.MAX_VALUE, 100
        ).getRows()).isEmpty();
    }

    @Test
    void detailQueryAndTrendRespectRequestedHorizonAndDateRange() {
        RiskScoreSnapshotEntity longTerm = snapshot(4L, "20-60d", "0.90", "stale", "watch");
        when(snapshotMapper.selectForObject("stock", "600519.SH", "20-60d", TRADE_DATE))
                .thenReturn(List.of(longTerm));
        when(evidenceMapper.selectBySnapshotIds(List.of(4L))).thenReturn(List.of());
        when(exposureMapper.selectActiveParents("stock", "600519.SH", TRADE_DATE, CALCULATED_AT))
                .thenReturn(List.of());

        var detail = service.objectDetail("stock", "600519.SH", "20-60d", TRADE_DATE);
        assertThat(detail.snapshot().horizon()).isEqualTo("20-60d");
        assertThat(detail.snapshots()).singleElement();

        LocalDate start = LocalDate.of(2026, 7, 1);
        when(snapshotMapper.selectTrend("stock", "600519.SH", "20-60d", start, TRADE_DATE))
                .thenReturn(List.of(longTerm));
        var trend = service.trend("stock", "600519.SH", "20-60d", start, TRADE_DATE);

        assertThat(trend).singleElement().satisfies(point -> {
            assertThat(point.tradeDate()).isEqualTo(TRADE_DATE);
            assertThat(point.level()).isEqualTo("watch");
            assertThat(point.totalScore()).isEqualByComparingTo("75");
        });
        verify(snapshotMapper).selectTrend("stock", "600519.SH", "20-60d", start, TRADE_DATE);
    }

    private RiskScoreSnapshotEntity snapshot(long id, String horizon, String completeness,
                                               String qualityStatus, String level) {
        return snapshot(id, horizon, completeness, qualityStatus, level, "stock", "600519.SH");
    }

    private RiskScoreSnapshotEntity snapshot(long id, String horizon, String completeness,
                                               String qualityStatus, String level,
                                               String objectType, String objectId) {
        RiskScoreSnapshotEntity entity = new RiskScoreSnapshotEntity();
        entity.setId(id);
        entity.setObjectType(objectType);
        entity.setObjectId(objectId);
        entity.setObjectName(objectId);
        entity.setHorizon(horizon);
        entity.setTradeDate(TRADE_DATE);
        entity.setVScore(new BigDecimal("70"));
        entity.setTScore(new BigDecimal("65"));
        entity.setSScore(new BigDecimal("55"));
        entity.setCScore(new BigDecimal("60"));
        entity.setAScore(new BigDecimal("45"));
        entity.setMScore(new BigDecimal("1.05"));
        entity.setTotalScore(new BigDecimal("75"));
        entity.setRiskLevel(level);
        entity.setRiskStage("stampede");
        entity.setCompleteness(new BigDecimal(completeness));
        entity.setRiskConfidence(new BigDecimal("0.88"));
        entity.setModelVersion("risk-v1");
        entity.setQualityStatus(qualityStatus);
        entity.setCalculatedAt(CALCULATED_AT);
        return entity;
    }

    private RiskScoreEvidenceEntity evidence(long id, long snapshotId, String dimension,
                                              String indicatorCode, String qualityStatus) {
        RiskScoreEvidenceEntity entity = new RiskScoreEvidenceEntity();
        entity.setId(id);
        entity.setSnapshotId(snapshotId);
        entity.setDimensionCode(dimension);
        entity.setIndicatorCode(indicatorCode);
        entity.setRawValue(new BigDecimal("12.5"));
        entity.setIndicatorScore(new BigDecimal("80"));
        entity.setWeightedContribution(new BigDecimal("12"));
        entity.setObservedAt(CALCULATED_AT.minusHours(1));
        entity.setAvailableAt(CALCULATED_AT);
        entity.setSource("test");
        entity.setQualityStatus(qualityStatus);
        return entity;
    }

    private RiskObjectExposureEntity exposure(String parentType, String parentId, String qualityStatus) {
        RiskObjectExposureEntity entity = new RiskObjectExposureEntity();
        entity.setId(1L);
        entity.setObjectType("stock");
        entity.setObjectId("600519.SH");
        entity.setParentObjectType(parentType);
        entity.setParentObjectId(parentId);
        entity.setExposureWeight(new BigDecimal("0.8"));
        entity.setValidFrom(LocalDate.of(2026, 1, 1));
        entity.setObservedAt(CALCULATED_AT.minusHours(1));
        entity.setAvailableAt(CALCULATED_AT);
        entity.setSource("test");
        entity.setQualityStatus(qualityStatus);
        return entity;
    }
}
