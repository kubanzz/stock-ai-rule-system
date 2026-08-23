package com.jx.tracker.risk.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDecisionSupportNotice;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreEvidenceEntity;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.persistence.mapper.RiskObjectExposureMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreEvidenceMapper;
import com.jx.tracker.risk.persistence.mapper.RiskScoreSnapshotMapper;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskLevelCount;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectDetail;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectListItem;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectRef;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskOverview;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskSnapshot;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskTrendPoint;
import com.jx.tracker.risk.query.ProvisionalRiskAssessmentCalculator.Assessment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class RiskAssessmentQueryServiceImpl implements RiskAssessmentQueryService {

    private static final BigDecimal FORMAL_CONCLUSION_THRESHOLD = new BigDecimal("0.80");
    private static final String DEFAULT_HORIZON = "1-5d";
    private static final List<String> LEVELS = List.of("normal", "watch", "warning", "critical");
    private static final Map<String, Integer> HORIZON_ORDER = Map.of(
            "1-5d", 1, "5-20d", 2, "20-60d", 3
    );
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final RiskScoreSnapshotMapper snapshotMapper;
    private final RiskScoreEvidenceMapper evidenceMapper;
    private final RiskObjectExposureMapper exposureMapper;
    private final ProvisionalRiskAssessmentCalculator provisionalCalculator =
            new ProvisionalRiskAssessmentCalculator();

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
    public RiskOverview overview(String horizon, LocalDate tradeDate) {
        String normalizedHorizon = normalizeHorizon(horizon, false);
        LocalDate resolvedDate = tradeDate != null
                ? tradeDate
                : snapshotMapper.selectLatestTradeDate(normalizedHorizon);
        if (resolvedDate == null) {
            List<RiskLevelCount> emptyCounts = LEVELS.stream()
                    .map(level -> new RiskLevelCount(level, 0L))
                    .toList();
            return new RiskOverview(
                    normalizedHorizon,
                    null,
                    emptyCounts,
                    null,
                    List.of(),
                    RiskDecisionSupportNotice.TEXT
            );
        }
        List<RiskScoreSnapshotEntity> rows = safeList(
                snapshotMapper.selectForOverview(normalizedHorizon, resolvedDate)
        ).stream()
                .filter(row -> normalizedHorizon.equals(row.getHorizon()))
                .filter(row -> resolvedDate.equals(row.getTradeDate()))
                .toList();

        List<RiskScoreSnapshotEntity> displayRows = rows.stream()
                .filter(row -> "market".equals(row.getObjectType()) || hasLevel(row, "critical"))
                .toList();
        Map<Long, List<RiskEvidence>> evidenceBySnapshot = loadEvidence(displayRows);
        int staleTradingDays = staleTradingDays(resolvedDate);
        RiskSnapshot marketSnapshot = rows.stream()
                .filter(row -> "market".equals(row.getObjectType()))
                .findFirst()
                .map(row -> toSnapshot(
                        row, evidenceBySnapshot.getOrDefault(row.getId(), List.of()), staleTradingDays))
                .orElse(null);
        List<RiskObjectListItem> highRiskObjects = rows.stream()
                .filter(row -> hasLevel(row, "critical"))
                .map(row -> toListItem(
                        row, evidenceBySnapshot.getOrDefault(row.getId(), List.of()), staleTradingDays))
                .toList();
        List<RiskLevelCount> counts = LEVELS.stream()
                .map(level -> new RiskLevelCount(
                        level,
                        rows.stream().filter(row -> hasLevel(row, level)).count()
                ))
                .toList();
        return new RiskOverview(
                normalizedHorizon,
                resolvedDate,
                counts,
                marketSnapshot,
                highRiskObjects,
                RiskDecisionSupportNotice.TEXT
        );
    }

    @Override
    public PageResult<RiskObjectListItem> listObjects(
            String objectType,
            String level,
            String horizon,
            LocalDate tradeDate,
            String keyword,
            String parentObjectType,
            String parentObjectId,
            int pageNum,
            int pageSize
    ) {
        validatePage(pageNum, pageSize);
        String normalizedType = normalizeObjectType(objectType, true);
        String normalizedLevel = normalizeLevel(level);
        String normalizedHorizon = normalizeHorizon(horizon, false);
        String normalizedKeyword = normalizeKeyword(keyword);
        ParentFilter parentFilter = normalizeParentFilter(parentObjectType, parentObjectId);
        LocalDate resolvedDate = tradeDate != null
                ? tradeDate
                : snapshotMapper.selectLatestTradeDate(normalizedHorizon);
        if (resolvedDate == null) {
            return PageResult.getDataTable(List.of(), 0L);
        }
        boolean allowPublishedParentFallback = tradeDate == null;
        long total = snapshotMapper.countObjectPage(
                normalizedType, normalizedLevel, normalizedHorizon, resolvedDate, normalizedKeyword,
                parentFilter.objectType(), parentFilter.objectId(), allowPublishedParentFallback
        );
        long offset = (long) (pageNum - 1) * pageSize;
        List<RiskScoreSnapshotEntity> rows = safeList(snapshotMapper.selectObjectPage(
                normalizedType,
                normalizedLevel,
                normalizedHorizon,
                resolvedDate,
                normalizedKeyword,
                parentFilter.objectType(),
                parentFilter.objectId(),
                allowPublishedParentFallback,
                offset,
                pageSize
        ));
        Map<Long, List<RiskEvidence>> evidenceBySnapshot = loadEvidence(rows);
        int staleTradingDays = staleTradingDays(resolvedDate);
        List<RiskObjectListItem> items = rows.stream()
                .map(row -> toListItem(
                        row, evidenceBySnapshot.getOrDefault(row.getId(), List.of()), staleTradingDays))
                .toList();
        return PageResult.getDataTable(items, total);
    }

    @Override
    public RiskObjectDetail objectDetail(
            String objectType,
            String objectId,
            String horizon,
            LocalDate tradeDate
    ) {
        String normalizedType = normalizeObjectType(objectType, false);
        String normalizedId = normalizeObjectId(objectId);
        String normalizedHorizon = normalizeHorizon(horizon, true);
        LocalDate resolvedDate = tradeDate != null
                ? tradeDate
                : snapshotMapper.selectLatestTradeDateForObject(
                        normalizedType, normalizedId, normalizedHorizon
                );
        if (resolvedDate == null) {
            throw snapshotNotFound(normalizedType, normalizedId);
        }
        List<RiskScoreSnapshotEntity> rows = safeList(snapshotMapper.selectForObject(
                normalizedType, normalizedId, normalizedHorizon, resolvedDate
        )).stream()
                .filter(row -> resolvedDate.equals(row.getTradeDate()))
                .filter(row -> normalizedHorizon == null || normalizedHorizon.equals(row.getHorizon()))
                .sorted(Comparator.comparingInt(row -> HORIZON_ORDER.getOrDefault(row.getHorizon(), 99)))
                .toList();
        if (rows.isEmpty()) {
            throw snapshotNotFound(normalizedType, normalizedId);
        }
        boolean allowPublishedParentFallback = tradeDate == null;
        Map<Long, List<RiskEvidence>> evidenceBySnapshot = loadEvidence(rows);
        int staleTradingDays = staleTradingDays(resolvedDate);
        List<RiskSnapshot> snapshots = rows.stream()
                .map(row -> toSnapshot(
                        row, evidenceBySnapshot.getOrDefault(row.getId(), List.of()), staleTradingDays))
                .toList();
        int selectedIndex = selectedSnapshotIndex(snapshots, normalizedHorizon);
        RiskScoreSnapshotEntity selectedRow = rows.get(selectedIndex);
        RiskSnapshot selectedSnapshot = snapshots.get(selectedIndex);
        List<String> activeTriggers = selectedSnapshot.evidence().stream()
                .filter(this::isActiveTrigger)
                .map(RiskEvidence::indicatorCode)
                .distinct()
                .toList();
        List<RiskObjectExposureEntity> parentRows = selectedRow.getCalculatedAt() == null
                ? List.of()
                : safeList(exposureMapper.selectActiveParents(
                        normalizedType,
                        normalizedId,
                        selectedRow.getTradeDate(),
                        selectedRow.getCalculatedAt()
                ));
        if (allowPublishedParentFallback
                && parentRows.isEmpty()
                && !"formal".equals(selectedSnapshot.conclusionStatus())) {
            parentRows = safeList(exposureMapper.selectLatestPublishedParents(
                    normalizedType, normalizedId, selectedRow.getTradeDate()));
        }
        List<RiskObjectRef> parentObjects = parentRows.stream()
                        .map(this::toParentRef)
                        .distinct()
                        .toList();
        return new RiskObjectDetail(
                objectName(selectedRow),
                selectedSnapshot.object(),
                selectedSnapshot,
                activeTriggers,
                parentObjects,
                snapshots
        );
    }

    @Override
    public List<RiskTrendPoint> trend(
            String objectType,
            String objectId,
            String horizon,
            LocalDate startDate,
            LocalDate endDate
    ) {
        String normalizedType = normalizeObjectType(objectType, false);
        String normalizedId = normalizeObjectId(objectId);
        String normalizedHorizon = normalizeHorizon(horizon, false);
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ServiceException("startDate 不能晚于 endDate", 400);
        }
        return safeList(snapshotMapper.selectTrend(
                normalizedType, normalizedId, normalizedHorizon, startDate, endDate
        )).stream()
                .filter(row -> normalizedHorizon.equals(row.getHorizon()))
                .filter(row -> startDate == null || !row.getTradeDate().isBefore(startDate))
                .filter(row -> endDate == null || !row.getTradeDate().isAfter(endDate))
                .map(this::toTrendPoint)
                .toList();
    }

    private RiskObjectListItem toListItem(
            RiskScoreSnapshotEntity row,
            List<RiskEvidence> evidence,
            int staleTradingDays
    ) {
        RiskSnapshot snapshot = toSnapshot(row, evidence, staleTradingDays);
        return new RiskObjectListItem(objectName(row), snapshot.object(), snapshot);
    }

    private RiskSnapshot toSnapshot(
            RiskScoreSnapshotEntity row,
            List<RiskEvidence> evidence,
            int staleTradingDays
    ) {
        BigDecimal completeness = row.getCompleteness() == null ? BigDecimal.ZERO : row.getCompleteness();
        boolean unavailable = isUnavailable(row.getQualityStatus());
        boolean formal = !unavailable
                && completeness.compareTo(FORMAL_CONCLUSION_THRESHOLD) >= 0;
        boolean dimensionUnavailable = RiskDataQualityStatus.UNAVAILABLE.getCode()
                .equals(row.getQualityStatus())
                || RiskDataQualityStatus.STALE.getCode().equals(row.getQualityStatus());
        Assessment assessment = provisionalCalculator.calculate(
                completeness,
                formal ? row.getTotalScore() : null,
                formal ? row.getRiskLevel() : null,
                dimensionUnavailable,
                evidence
        );
        java.time.LocalDateTime dataAsOf = assessment.dataAsOf() != null
                ? assessment.dataAsOf()
                : row.getAvailableAt() != null ? row.getAvailableAt() : row.getCalculatedAt();
        BigDecimal displayCompleteness = completeness.max(
                assessment.evidenceCompleteness());
        return new RiskSnapshot(
                new RiskObjectRef(row.getObjectType(), row.getObjectId()),
                row.getHorizon(),
                row.getTradeDate(),
                dimensionUnavailable ? null : row.getVScore(),
                dimensionUnavailable ? null : row.getTScore(),
                dimensionUnavailable ? null : row.getSScore(),
                dimensionUnavailable ? null : row.getCScore(),
                dimensionUnavailable ? null : row.getAScore(),
                dimensionUnavailable ? null : row.getMScore(),
                formal ? row.getTotalScore() : null,
                formal ? row.getRiskLevel() : null,
                formal ? row.getRiskStage() : null,
                displayCompleteness,
                formal ? row.getRiskConfidence() : null,
                List.copyOf(evidence),
                row.getModelVersion(),
                row.getCalculatedAt(),
                assessment.conclusionStatus(),
                assessment.provisionalScore(),
                assessment.provisionalLevel(),
                assessment.dimensions(),
                dataAsOf,
                staleTradingDays
        );
    }

    private RiskTrendPoint toTrendPoint(RiskScoreSnapshotEntity row) {
        RiskSnapshot snapshot = toSnapshot(row, List.of(), 0);
        return new RiskTrendPoint(
                snapshot.tradeDate(),
                snapshot.vScore(),
                snapshot.tScore(),
                snapshot.sScore(),
                snapshot.cScore(),
                snapshot.aScore(),
                snapshot.totalScore(),
                snapshot.level(),
                snapshot.completeness()
        );
    }

    private Map<Long, List<RiskEvidence>> loadEvidence(List<RiskScoreSnapshotEntity> snapshots) {
        List<Long> ids = snapshots.stream()
                .map(RiskScoreSnapshotEntity::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return safeList(
                evidenceMapper.selectBySnapshotIds(ids)).stream()
                .filter(row -> row.getSnapshotId() != null)
                .collect(Collectors.groupingBy(
                        RiskScoreEvidenceEntity::getSnapshotId,
                        LinkedHashMap::new,
                        Collectors.mapping(this::toEvidence, Collectors.toList())
                ));
    }

    private int staleTradingDays(LocalDate tradeDate) {
        return tradeDate == null ? 0 : Math.max(0, snapshotMapper.countOpenTradingDaysAfter(tradeDate));
    }

    private RiskEvidence toEvidence(RiskScoreEvidenceEntity row) {
        boolean unavailable = isUnavailable(row.getQualityStatus());
        Map<String, Object> details = new LinkedHashMap<>(readJson(row.getEvidenceJson()));
        if (row.getLayerObjectType() != null && row.getLayerObjectId() != null) {
            details.put("layerObjectType", row.getLayerObjectType());
            details.put("layerObjectId", row.getLayerObjectId());
        }
        return new RiskEvidence(
                row.getDimensionCode(),
                row.getIndicatorCode(),
                unavailable ? null : row.getRawValue(),
                unavailable ? null : row.getIndicatorScore(),
                row.getObservedAt(),
                row.getAvailableAt(),
                row.getSource(),
                defaultQuality(row.getQualityStatus()),
                details
        );
    }

    private RiskObjectRef toParentRef(RiskObjectExposureEntity row) {
        return new RiskObjectRef(row.getParentObjectType(), row.getParentObjectId());
    }

    private boolean isActiveTrigger(RiskEvidence evidence) {
        return "T".equals(evidence.dimension())
                && "available".equals(evidence.qualityStatus())
                && evidence.score() != null
                && evidence.score().signum() > 0;
    }

    private boolean hasLevel(RiskScoreSnapshotEntity row, String level) {
        return !isUnavailable(row.getQualityStatus())
                && row.getCompleteness() != null
                && row.getCompleteness().compareTo(FORMAL_CONCLUSION_THRESHOLD) >= 0
                && level.equals(row.getRiskLevel());
    }

    private int selectedSnapshotIndex(List<RiskSnapshot> snapshots, String horizon) {
        String selectedHorizon = horizon == null ? DEFAULT_HORIZON : horizon;
        for (int index = 0; index < snapshots.size(); index++) {
            if (selectedHorizon.equals(snapshots.get(index).horizon())) {
                return index;
            }
        }
        return 0;
    }

    private String objectName(RiskScoreSnapshotEntity row) {
        return row.getObjectName() == null || row.getObjectName().isBlank()
                ? row.getObjectId()
                : row.getObjectName();
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

    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String normalized = level.trim().toLowerCase(Locale.ROOT);
        if (!RiskLevel.codes().contains(normalized)) {
            throw new ServiceException("level 仅支持 " + RiskLevel.codes(), 400);
        }
        return normalized;
    }

    private String normalizeHorizon(String horizon, boolean nullable) {
        if (horizon == null || horizon.isBlank()) {
            return nullable ? null : DEFAULT_HORIZON;
        }
        String normalized = horizon.trim().toLowerCase(Locale.ROOT);
        if (!RiskHorizon.codes().contains(normalized)) {
            throw new ServiceException("horizon 仅支持 " + RiskHorizon.codes(), 400);
        }
        return normalized;
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String normalizeObjectId(String objectId) {
        if (objectId == null || objectId.isBlank()) {
            throw new ServiceException("objectId 不能为空", 400);
        }
        return objectId.trim();
    }

    private ParentFilter normalizeParentFilter(String objectType, String objectId) {
        boolean missingType = objectType == null || objectType.isBlank();
        boolean missingId = objectId == null || objectId.isBlank();
        if (missingType && missingId) {
            return new ParentFilter(null, null);
        }
        if (missingType || missingId) {
            throw new ServiceException(
                    "parentObjectType 与 parentObjectId 必须同时提供",
                    400
            );
        }
        return new ParentFilter(
                normalizeObjectType(objectType, false),
                normalizeObjectId(objectId)
        );
    }

    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new ServiceException("pageNum 必须大于等于 1", 400);
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException("pageSize 必须在 1 到 100 之间", 400);
        }
    }

    private ServiceException snapshotNotFound(String objectType, String objectId) {
        return new ServiceException("未找到风险对象快照 " + objectType + "/" + objectId, 404);
    }

    private boolean isUnavailable(String qualityStatus) {
        return RiskDataQualityStatus.UNAVAILABLE.getCode().equals(qualityStatus)
                || RiskDataQualityStatus.STALE.getCode().equals(qualityStatus)
                || RiskDataQualityStatus.INSUFFICIENT_HISTORY.getCode().equals(qualityStatus);
    }

    private String defaultQuality(String qualityStatus) {
        return qualityStatus == null || qualityStatus.isBlank() ? "unavailable" : qualityStatus;
    }

    private <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private record ParentFilter(String objectType, String objectId) {
    }
}
