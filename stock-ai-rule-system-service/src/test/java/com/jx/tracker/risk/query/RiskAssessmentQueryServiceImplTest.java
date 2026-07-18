package com.jx.tracker.risk.query;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreEvidenceEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.persistence.mapper.RiskObjectExposureMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreEvidenceMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreSnapshotMapper;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectSummary;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskPeriodAssessment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    void detailAlwaysReturnsThreePeriodsAndProtectsIncompleteOrUnavailableConclusions() {
        RiskScoreSnapshotEntity incomplete = snapshot(1L, "1-5d", "0.79", "available", "warning");
        RiskScoreSnapshotEntity unavailable = snapshot(2L, "5-20d", "0.95", "unavailable", "critical");
        when(snapshotMapper.selectLatestForObject("stock", "600519.SH"))
                .thenReturn(List.of(incomplete, unavailable));

        RiskScoreEvidenceEntity unavailableEvidence = evidence(11L, 1L, "C", "price_confirmation", "unavailable");
        RiskScoreEvidenceEntity staleTrigger = evidence(12L, 2L, "T", "earnings_warning", "stale");
        RiskScoreEvidenceEntity activeTrigger = evidence(13L, 2L, "T", "credit_event", "available");
        when(evidenceMapper.selectBySnapshotIds(List.of(1L, 2L)))
                .thenReturn(List.of(unavailableEvidence, staleTrigger, activeTrigger));
        when(exposureMapper.selectActiveParents("stock", "600519.SH", TRADE_DATE))
                .thenReturn(List.of(exposure("sector", "BK0475", "stale")));

        RiskObjectDetail detail = service.objectDetail("stock", "600519.SH");

        assertThat(detail.periods()).hasSize(3);
        RiskPeriodAssessment shortTerm = detail.periods().get(0);
        assertThat(shortTerm.horizon()).isEqualTo("1-5d");
        assertThat(shortTerm.totalScore()).isNull();
        assertThat(shortTerm.riskLevel()).isNull();
        assertThat(shortTerm.riskStage()).isNull();
        assertThat(shortTerm.riskConfidence()).isNull();
        assertThat(shortTerm.evidence().getFirst().qualityStatus()).isEqualTo("unavailable");
        assertThat(shortTerm.evidence().getFirst().rawValue()).isNull();
        assertThat(shortTerm.evidence().getFirst().indicatorScore()).isNull();

        RiskPeriodAssessment mediumTerm = detail.periods().get(1);
        assertThat(mediumTerm.qualityStatus()).isEqualTo("unavailable");
        assertThat(mediumTerm.totalScore()).isNull();
        assertThat(detail.activeTriggers()).extracting("indicatorCode")
                .containsExactly("credit_event");
        assertThat(detail.parents()).singleElement().satisfies(parent -> {
            assertThat(parent.parentObjectId()).isEqualTo("BK0475");
            assertThat(parent.qualityStatus()).isEqualTo("stale");
        });
        assertThat(detail.riskNotice()).isEqualTo(RiskDecisionSupportNotice.TEXT);
    }

    @Test
    void emptyDetailUsesUnavailablePlaceholdersInsteadOfZeroes() {
        when(snapshotMapper.selectLatestForObject("market", "000001.SH")).thenReturn(List.of());

        RiskObjectDetail detail = service.objectDetail("market", "000001.SH");

        assertThat(detail.tradeDate()).isNull();
        assertThat(detail.periods()).hasSize(3).allSatisfy(period -> {
            assertThat(period.qualityStatus()).isEqualTo("unavailable");
            assertThat(period.totalScore()).isNull();
            assertThat(period.vScore()).isNull();
            assertThat(period.completeness()).isEqualByComparingTo("0");
        });
        assertThat(detail.activeTriggers()).isEmpty();
        assertThat(detail.parents()).isEmpty();
    }

    @Test
    void overviewCountsObjectsByHighestCurrentLevelAndListsOnlyCriticalObjects() {
        when(snapshotMapper.selectForOverview(TRADE_DATE)).thenReturn(List.of(
                snapshot(1L, "1-5d", "0.90", "available", "warning"),
                snapshot(2L, "5-20d", "0.90", "available", "critical"),
                snapshot(3L, "1-5d", "0.90", "available", "normal", "sector", "BK0001")
        ));

        var overview = service.overview(TRADE_DATE);

        assertThat(overview.levelCounts()).containsEntry("critical", 1L).containsEntry("normal", 1L);
        assertThat(overview.highRiskObjectCount()).isEqualTo(1);
        assertThat(overview.highRiskObjects()).singleElement()
                .extracting(RiskObjectSummary::objectId).isEqualTo("600519.SH");
    }

    @Test
    void objectListFiltersThenPaginatesAndTrendDelegatesDateRange() {
        when(snapshotMapper.selectLatestObjects("stock")).thenReturn(List.of(
                snapshot(1L, "1-5d", "0.90", "available", "critical", "stock", "600519.SH"),
                snapshot(2L, "1-5d", "0.90", "available", "critical", "stock", "000001.SZ")
        ));

        PageResult<RiskObjectSummary> page = service.listObjects("stock", "critical", 2, 1);

        assertThat(page.getCode()).isEqualTo(200);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getRows()).singleElement().extracting(RiskObjectSummary::objectId)
                .isEqualTo("600519.SH");

        PageResult<RiskObjectSummary> distantPage = service.listObjects(
                "stock", "critical", Integer.MAX_VALUE, 100
        );
        assertThat(distantPage.getRows()).isEmpty();
        assertThat(distantPage.getTotal()).isEqualTo(2);

        LocalDate start = LocalDate.of(2026, 7, 1);
        when(snapshotMapper.selectTrend("stock", "600519.SH", start, TRADE_DATE))
                .thenReturn(List.of(snapshot(4L, "1-5d", "0.90", "stale", "watch")));
        var trend = service.trend("stock", "600519.SH", start, TRADE_DATE);

        assertThat(trend.points()).singleElement().satisfies(point -> {
            assertThat(point.tradeDate()).isEqualTo(TRADE_DATE);
            assertThat(point.qualityStatus()).isEqualTo("stale");
        });
        verify(snapshotMapper).selectTrend("stock", "600519.SH", start, TRADE_DATE);
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
