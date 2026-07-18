package com.jx.tracker.risk.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreEvidenceEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.persistence.mapper.RiskObjectExposureMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreEvidenceMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreSnapshotMapper;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.Overview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidenceItem;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectSummary;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskParent;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskPeriodAssessment;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrend;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrendPoint;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class RiskAssessmentQueryServiceImpl implements RiskAssessmentQueryService {

    private static final BigDecimal FORMAL_CONCLUSION_THRESHOLD = new BigDecimal("0.80");
    private static final List<String> HORIZONS = List.of("1-5d", "5-20d", "20-60d");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final RiskScoreSnapshotMapper snapshotMapper;
    private final RiskScoreEvidenceMapper evidenceMapper;
    private final RiskObjectExposureMapper exposureMapper;

    public RiskAssessmentQueryServiceImpl(
            RiskScoreSnapshotMapper snapshotMapper,
            RiskScoreEvidenceMapper evidenceMapper,
            RiskObjectExposureMapper exposureMapper
    ) {
        this.snapshotMapper = snapshotMapper;
        this.evidenceMapper = evidenceMapper;
        this.exposureMapper = exposureMapper;
    }

    @Override
    public Overview overview(LocalDate tradeDate) {
        List<RiskObjectSummary> summaries = summaries(snapshotMapper.selectForOverview(tradeDate));
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (String level : List.of("normal", "watch", "warning", "critical", "unavailable")) {
            counts.put(level, 0L);
        }
        for (RiskObjectSummary summary : summaries) {
            String level = summary.riskLevel() == null ? "unavailable" : summary.riskLevel();
            counts.compute(level, (ignored, count) -> count == null ? 1L : count + 1L);
        }
        List<RiskObjectSummary> highRiskObjects = summaries.stream()
                .filter(summary -> "critical".equals(summary.riskLevel()))
                .toList();
        LocalDate resolvedDate = tradeDate != null ? tradeDate : summaries.stream()
                .map(RiskObjectSummary::tradeDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        return new Overview(
                resolvedDate,
                Map.copyOf(counts),
                highRiskObjects.size(),
                highRiskObjects,
                RiskDecisionSupportNotice.TEXT
        );
    }

    @Override
    public PageResult<RiskObjectSummary> listObjects(
            String objectType,
            String riskLevel,
            int pageNum,
            int pageSize
    ) {
        validatePage(pageNum, pageSize);
        String normalizedType = normalizeObjectType(objectType, true);
        String normalizedLevel = normalizeRiskLevel(riskLevel);
        List<RiskObjectSummary> filtered = summaries(snapshotMapper.selectLatestObjects(normalizedType)).stream()
                .filter(summary -> normalizedLevel == null || normalizedLevel.equals(summary.riskLevel()))
                .toList();
        long offset = (long) (pageNum - 1) * pageSize;
        int from = (int) Math.min(offset, filtered.size());
        int to = Math.min(from + pageSize, filtered.size());
        return PageResult.getDataTable(filtered.subList(from, to), (long) filtered.size());
    }

    @Override
    public RiskObjectDetail objectDetail(String objectType, String objectId) {
        String normalizedType = normalizeObjectType(objectType, false);
        String normalizedId = normalizeObjectId(objectId);
        List<RiskScoreSnapshotEntity> snapshots = safeList(
                snapshotMapper.selectLatestForObject(normalizedType, normalizedId)
        );
        LocalDate tradeDate = snapshots.stream()
                .map(RiskScoreSnapshotEntity::getTradeDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        Map<Long, List<RiskEvidenceItem>> evidenceBySnapshot = loadEvidence(snapshots);
        List<RiskPeriodAssessment> periods = periods(snapshots, evidenceBySnapshot);
        List<RiskEvidenceItem> activeTriggers = periods.stream()
                .flatMap(period -> period.evidence().stream())
                .filter(this::isActiveTrigger)
                .toList();
        List<RiskParent> parents = tradeDate == null
                ? List.of()
                : safeList(exposureMapper.selectActiveParents(normalizedType, normalizedId, tradeDate)).stream()
                        .map(this::toParent)
                        .toList();
        return new RiskObjectDetail(
                normalizedType,
                normalizedId,
                tradeDate,
                periods,
                activeTriggers,
                parents,
                RiskDecisionSupportNotice.TEXT
        );
    }

    @Override
    public RiskTrend trend(String objectType, String objectId, LocalDate startDate, LocalDate endDate) {
        String normalizedType = normalizeObjectType(objectType, false);
        String normalizedId = normalizeObjectId(objectId);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ServiceException("startDate 不能晚于 endDate", 400);
        }
        List<RiskTrendPoint> points = safeList(
                snapshotMapper.selectTrend(normalizedType, normalizedId, startDate, endDate)
        ).stream().map(this::toTrendPoint).toList();
        return new RiskTrend(
                normalizedType,
                normalizedId,
                startDate,
                endDate,
                points,
                RiskDecisionSupportNotice.TEXT
        );
    }

    private List<RiskObjectSummary> summaries(List<RiskScoreSnapshotEntity> snapshots) {
        Map<ObjectKey, List<RiskScoreSnapshotEntity>> grouped = safeList(snapshots).stream()
                .collect(Collectors.groupingBy(
                        row -> new ObjectKey(row.getObjectType(), row.getObjectId()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        return grouped.entrySet().stream()
                .map(entry -> toSummary(entry.getKey(), entry.getValue()))
                .sorted(summaryComparator())
                .toList();
    }

    private RiskObjectSummary toSummary(ObjectKey key, List<RiskScoreSnapshotEntity> snapshots) {
        List<RiskPeriodAssessment> periods = periods(snapshots, Map.of());
        RiskPeriodAssessment representative = periods.stream()
                .filter(period -> period.riskLevel() != null)
                .max(Comparator.comparingInt((RiskPeriodAssessment period) -> levelRank(period.riskLevel()))
                        .thenComparing(period -> period.totalScore() == null ? BigDecimal.ZERO : period.totalScore()))
                .orElse(null);
        LocalDate tradeDate = snapshots.stream()
                .map(RiskScoreSnapshotEntity::getTradeDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        return new RiskObjectSummary(
                key.objectType,
                key.objectId,
                tradeDate,
                representative == null ? null : representative.riskLevel(),
                representative == null ? null : representative.totalScore(),
                periods,
                RiskDecisionSupportNotice.TEXT
        );
    }

    private List<RiskPeriodAssessment> periods(
            List<RiskScoreSnapshotEntity> snapshots,
            Map<Long, List<RiskEvidenceItem>> evidenceBySnapshot
    ) {
        Map<String, RiskScoreSnapshotEntity> byHorizon = safeList(snapshots).stream()
                .filter(row -> row.getHorizon() != null)
                .collect(Collectors.toMap(
                        RiskScoreSnapshotEntity::getHorizon,
                        Function.identity(),
                        this::newerSnapshot
                ));
        return HORIZONS.stream()
                .map(horizon -> {
                    RiskScoreSnapshotEntity row = byHorizon.get(horizon);
                    return row == null
                            ? unavailablePeriod(horizon)
                            : toPeriod(row, evidenceBySnapshot.getOrDefault(row.getId(), List.of()));
                })
                .toList();
    }

    private RiskScoreSnapshotEntity newerSnapshot(
            RiskScoreSnapshotEntity left,
            RiskScoreSnapshotEntity right
    ) {
        if (left.getCalculatedAt() == null) {
            return right;
        }
        if (right.getCalculatedAt() == null) {
            return left;
        }
        return right.getCalculatedAt().isAfter(left.getCalculatedAt()) ? right : left;
    }

    private RiskPeriodAssessment toPeriod(
            RiskScoreSnapshotEntity row,
            List<RiskEvidenceItem> evidence
    ) {
        BigDecimal completeness = row.getCompleteness() == null ? BigDecimal.ZERO : row.getCompleteness();
        boolean unavailable = isUnavailable(row.getQualityStatus());
        boolean formalConclusion = !unavailable
                && completeness.compareTo(FORMAL_CONCLUSION_THRESHOLD) >= 0;
        return new RiskPeriodAssessment(
                row.getHorizon(),
                row.getTradeDate(),
                unavailable ? null : row.getVScore(),
                unavailable ? null : row.getTScore(),
                unavailable ? null : row.getSScore(),
                unavailable ? null : row.getCScore(),
                unavailable ? null : row.getAScore(),
                unavailable ? null : row.getMScore(),
                formalConclusion ? row.getTotalScore() : null,
                formalConclusion ? row.getRiskLevel() : null,
                formalConclusion ? row.getRiskStage() : null,
                completeness,
                formalConclusion ? row.getRiskConfidence() : null,
                defaultQuality(row.getQualityStatus()),
                List.copyOf(evidence),
                row.getModelVersion(),
                row.getCalculatedAt()
        );
    }

    private RiskPeriodAssessment unavailablePeriod(String horizon) {
        return new RiskPeriodAssessment(
                horizon, null, null, null, null, null, null, null, null, null, null,
                BigDecimal.ZERO, null, "unavailable", List.of(), null, null
        );
    }

    private Map<Long, List<RiskEvidenceItem>> loadEvidence(List<RiskScoreSnapshotEntity> snapshots) {
        List<Long> snapshotIds = snapshots.stream()
                .map(RiskScoreSnapshotEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (snapshotIds.isEmpty()) {
            return Map.of();
        }
        return safeList(evidenceMapper.selectBySnapshotIds(snapshotIds)).stream()
                .filter(row -> row.getSnapshotId() != null)
                .collect(Collectors.groupingBy(
                        RiskScoreEvidenceEntity::getSnapshotId,
                        LinkedHashMap::new,
                        Collectors.mapping(this::toEvidence, Collectors.toList())
                ));
    }

    private RiskEvidenceItem toEvidence(RiskScoreEvidenceEntity row) {
        boolean unavailable = isUnavailable(row.getQualityStatus());
        return new RiskEvidenceItem(
                row.getDimensionCode(),
                row.getIndicatorCode(),
                unavailable ? null : row.getRawValue(),
                unavailable ? null : row.getIndicatorScore(),
                unavailable ? null : row.getWeightedContribution(),
                row.getObservedAt(),
                row.getAvailableAt(),
                row.getSource(),
                defaultQuality(row.getQualityStatus()),
                readJson(row.getEvidenceJson())
        );
    }

    private RiskParent toParent(RiskObjectExposureEntity row) {
        return new RiskParent(
                row.getParentObjectType(),
                row.getParentObjectId(),
                row.getExposureWeight(),
                row.getValidFrom(),
                row.getValidTo(),
                row.getObservedAt(),
                row.getAvailableAt(),
                row.getSource(),
                defaultQuality(row.getQualityStatus()),
                readJson(row.getMetadataJson())
        );
    }

    private RiskTrendPoint toTrendPoint(RiskScoreSnapshotEntity row) {
        RiskPeriodAssessment period = toPeriod(row, List.of());
        return new RiskTrendPoint(
                period.horizon(),
                period.tradeDate(),
                period.vScore(),
                period.tScore(),
                period.sScore(),
                period.cScore(),
                period.aScore(),
                period.mScore(),
                period.totalScore(),
                period.riskLevel(),
                period.riskStage(),
                period.completeness(),
                period.riskConfidence(),
                period.qualityStatus(),
                period.modelVersion(),
                period.calculatedAt()
        );
    }

    private boolean isActiveTrigger(RiskEvidenceItem evidence) {
        return "T".equals(evidence.dimensionCode())
                && "available".equals(evidence.qualityStatus())
                && evidence.indicatorScore() != null
                && evidence.indicatorScore().signum() > 0;
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return Map.of("raw", json);
        }
    }

    private Comparator<RiskObjectSummary> summaryComparator() {
        return Comparator.comparingInt((RiskObjectSummary summary) -> levelRank(summary.riskLevel()))
                .reversed()
                .thenComparing(
                        RiskObjectSummary::totalScore,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )
                .thenComparing(RiskObjectSummary::objectType)
                .thenComparing(RiskObjectSummary::objectId);
    }

    private int levelRank(String level) {
        return switch (level == null ? "" : level) {
            case "critical" -> 4;
            case "warning" -> 3;
            case "watch" -> 2;
            case "normal" -> 1;
            default -> 0;
        };
    }

    private String normalizeObjectType(String objectType, boolean nullable) {
        if (objectType == null || objectType.isBlank()) {
            if (nullable) {
                return null;
            }
            throw new ServiceException("objectType 不能为空", 400);
        }
        String normalized = objectType.trim().toLowerCase(Locale.ROOT);
        if (!RiskObjectType.codes().contains(normalized)) {
            throw new ServiceException("objectType 仅支持 " + RiskObjectType.codes(), 400);
        }
        return normalized;
    }

    private String normalizeRiskLevel(String riskLevel) {
        if (riskLevel == null || riskLevel.isBlank()) {
            return null;
        }
        String normalized = riskLevel.trim().toLowerCase(Locale.ROOT);
        if (!RiskLevel.codes().contains(normalized)) {
            throw new ServiceException("riskLevel 仅支持 " + RiskLevel.codes(), 400);
        }
        return normalized;
    }

    private String normalizeObjectId(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            throw new ServiceException("objectId 不能为空", 400);
        }
        return objectId.trim();
    }

    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new ServiceException("pageNum 必须大于等于 1", 400);
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException("pageSize 必须在 1 到 100 之间", 400);
        }
    }

    private boolean isUnavailable(String qualityStatus) {
        return RiskDataQualityStatus.UNAVAILABLE.getCode().equals(qualityStatus)
                || RiskDataQualityStatus.INSUFFICIENT_HISTORY.getCode().equals(qualityStatus);
    }

    private String defaultQuality(String qualityStatus) {
        return qualityStatus == null || qualityStatus.isBlank() ? "unavailable" : qualityStatus;
    }

    private <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private record ObjectKey(String objectType, String objectId) {
    }
}
