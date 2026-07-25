package com.jx.tracker.risk.query;

import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorComponentCatalog;
import com.jx.tracker.risk.persistence.entity.RiskObjectExposureEntity;
import com.jx.tracker.risk.persistence.entity.RiskProvisionalObservationRow;
import com.jx.tracker.risk.persistence.entity.RiskScoreSnapshotEntity;
import com.jx.tracker.risk.persistence.mapper.RiskObjectExposureMapper;
import com.jx.tracker.risk.persistence.mapper.RiskProvisionalObservationMapper;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskEvidence;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 使用真实当前值与不完整历史生成查询层暂定证据。结果不写入快照和闸门。
 */
@Component
public class JdbcProvisionalRiskEvidenceProvider implements ProvisionalRiskEvidenceProvider {

    private static final int MIN_HISTORY_SAMPLES = 20;
    private static final int MIN_CROSS_SECTION_SAMPLES = 10;
    private static final int MAX_HISTORY_SAMPLES = 1250;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final RiskProvisionalObservationMapper observationMapper;
    private final RiskObjectExposureMapper exposureMapper;

    public JdbcProvisionalRiskEvidenceProvider(
            RiskProvisionalObservationMapper observationMapper,
            RiskObjectExposureMapper exposureMapper
    ) {
        this.observationMapper = Objects.requireNonNull(observationMapper);
        this.exposureMapper = Objects.requireNonNull(exposureMapper);
    }

    @Override
    public Map<Long, List<RiskEvidence>> load(
            List<RiskScoreSnapshotEntity> snapshots,
            boolean allowPublishedExposureFallback
    ) {
        if (snapshots == null || snapshots.isEmpty()) {
            return Map.of();
        }
        Map<CrossSectionKey, List<RiskProvisionalObservationRow>> crossSections =
                new LinkedHashMap<>();
        Map<Long, List<RiskEvidence>> result = new LinkedHashMap<>();
        for (RiskScoreSnapshotEntity snapshot : snapshots) {
            if (snapshot == null || snapshot.getId() == null
                    || snapshot.getTradeDate() == null || snapshot.getCalculatedAt() == null) {
                continue;
            }
            List<RiskProvisionalObservationRow> crossSection = crossSections.computeIfAbsent(
                    new CrossSectionKey(
                            snapshot.getHorizon(),
                            snapshot.getTradeDate(),
                            snapshot.getCalculatedAt()
                    ),
                    key -> safeList(observationMapper.selectSectorCrossSection(
                            key.horizon(), key.tradeDate(), key.asOf()))
            );
            List<RiskEvidence> evidence = new ArrayList<>();
            for (LayerTarget layer : layers(
                    snapshot, allowPublishedExposureFallback)) {
                List<RiskProvisionalObservationRow> history = safeList(
                        observationMapper.selectHistory(
                                layer.objectType(),
                                layer.objectId(),
                                snapshot.getHorizon(),
                                snapshot.getTradeDate().minusYears(6),
                                snapshot.getTradeDate(),
                                snapshot.getCalculatedAt()
                        )
                );
                evidence.addAll(assemble(
                        layer, snapshot.getTradeDate(), history, crossSection));
            }
            if (!evidence.isEmpty()) {
                result.put(snapshot.getId(), List.copyOf(evidence));
            }
        }
        return Map.copyOf(result);
    }

    private List<LayerTarget> layers(
            RiskScoreSnapshotEntity snapshot,
            boolean allowPublishedExposureFallback
    ) {
        if (!"stock".equals(snapshot.getObjectType())) {
            return List.of(new LayerTarget(
                    snapshot.getObjectType(), snapshot.getObjectId(), BigDecimal.ONE));
        }
        List<LayerTarget> layers = new ArrayList<>();
        layers.add(new LayerTarget("market", "CN-A", new BigDecimal("0.25")));
        List<RiskObjectExposureEntity> parents = safeList(exposureMapper.selectActiveParents(
                snapshot.getObjectType(),
                snapshot.getObjectId(),
                snapshot.getTradeDate(),
                snapshot.getCalculatedAt()
        ));
        if (parents.isEmpty() && allowPublishedExposureFallback) {
            parents = safeList(exposureMapper.selectLatestPublishedParents(
                    snapshot.getObjectType(),
                    snapshot.getObjectId(),
                    snapshot.getTradeDate()
            ));
        }
        parents.stream()
                .filter(parent -> "sector".equals(parent.getParentObjectType()))
                .findFirst()
                .ifPresent(parent -> layers.add(new LayerTarget(
                        "sector", parent.getParentObjectId(), new BigDecimal("0.35"))));
        layers.add(new LayerTarget(
                snapshot.getObjectType(), snapshot.getObjectId(), new BigDecimal("0.40")));
        return List.copyOf(layers);
    }

    private List<RiskEvidence> assemble(
            LayerTarget layer,
            LocalDate tradeDate,
            List<RiskProvisionalObservationRow> history,
            List<RiskProvisionalObservationRow> crossSection
    ) {
        Map<ComponentKey, List<RiskProvisionalObservationRow>> historyByComponent =
                history.stream()
                        .filter(row -> row.getActualValue() != null)
                        .collect(Collectors.groupingBy(
                                row -> new ComponentKey(
                                        row.getIndicatorCode(), row.getComponentCode()),
                                LinkedHashMap::new,
                                Collectors.toList()
                        ));
        Map<ComponentKey, RiskProvisionalObservationRow> current = new LinkedHashMap<>();
        historyByComponent.forEach((key, rows) -> rows.stream()
                .filter(row -> tradeDate.equals(row.getTradeDate()))
                .max(Comparator.comparing(RiskProvisionalObservationRow::getAvailableAt))
                .ifPresent(row -> current.put(key, row)));
        Map<ComponentKey, List<BigDecimal>> crossValues = crossSection.stream()
                .filter(row -> row.getActualValue() != null)
                .collect(Collectors.groupingBy(
                        row -> new ComponentKey(
                                row.getIndicatorCode(), row.getComponentCode()),
                        LinkedHashMap::new,
                        Collectors.mapping(
                                RiskProvisionalObservationRow::getActualValue,
                                Collectors.toList()
                        )
                ));

        Map<String, Map<String, RiskProvisionalObservationRow>> currentByIndicator =
                new LinkedHashMap<>();
        current.forEach((key, row) -> currentByIndicator
                .computeIfAbsent(key.indicatorCode(), ignored -> new LinkedHashMap<>())
                .put(key.componentCode(), row));
        List<RiskEvidence> evidence = new ArrayList<>();
        currentByIndicator.forEach((indicatorCode, components) ->
                buildEvidence(
                        layer, indicatorCode, components, historyByComponent, crossValues)
                        .ifPresent(evidence::add));
        return List.copyOf(evidence);
    }

    private java.util.Optional<RiskEvidence> buildEvidence(
            LayerTarget layer,
            String indicatorCode,
            Map<String, RiskProvisionalObservationRow> current,
            Map<ComponentKey, List<RiskProvisionalObservationRow>> history,
            Map<ComponentKey, List<BigDecimal>> crossValues
    ) {
        try {
            RiskIndicatorCatalog.require(indicatorCode);
        } catch (IllegalArgumentException ignored) {
            return java.util.Optional.empty();
        }
        List<String> expected = RiskIndicatorComponentCatalog.components(indicatorCode).stream()
                .map(RiskIndicatorComponentCatalog.ComponentDefinition::code)
                .toList();
        if (expected.isEmpty()) {
            expected = List.copyOf(current.keySet());
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(current.keySet());
        if (!missing.isEmpty()) {
            return java.util.Optional.empty();
        }

        List<ComponentScore> scores = new ArrayList<>();
        for (String componentCode : expected) {
            RiskProvisionalObservationRow row = current.get(componentCode);
            ComponentKey key = new ComponentKey(indicatorCode, componentCode);
            List<BigDecimal> ownValues = latestValues(
                    history.getOrDefault(key, List.of()));
            String normalization;
            List<BigDecimal> baseline;
            if (ownValues.size() >= MIN_HISTORY_SAMPLES) {
                baseline = ownValues;
                normalization = ownValues.size() >= MAX_HISTORY_SAMPLES
                        ? "rolling_percentile"
                        : "partial_rolling_percentile";
            } else if ("sector".equals(layer.objectType())
                    && crossValues.getOrDefault(key, List.of()).size()
                    >= MIN_CROSS_SECTION_SAMPLES) {
                baseline = crossValues.get(key);
                normalization = "current_cross_section_percentile";
            } else {
                return java.util.Optional.empty();
            }
            BigDecimal percentile = percentile(row.getActualValue(), baseline);
            BigDecimal oriented = orient(indicatorCode, componentCode, percentile);
            scores.add(new ComponentScore(
                    componentCode,
                    row.getActualValue(),
                    oriented,
                    baseline.size(),
                    normalization,
                    row
            ));
        }

        RiskProvisionalObservationRow representative = scores.stream()
                .map(ComponentScore::row)
                .max(Comparator.comparing(RiskProvisionalObservationRow::getAvailableAt))
                .orElseThrow();
        BigDecimal score = average(scores.stream().map(ComponentScore::score).toList());
        BigDecimal rawValue = average(scores.stream().map(ComponentScore::rawValue).toList());
        Map<String, Object> details = details(layer, scores);
        return java.util.Optional.of(new RiskEvidence(
                representative.getDimensionCode(),
                indicatorCode,
                rawValue,
                score,
                representative.getObservedAt(),
                representative.getAvailableAt(),
                representative.getSource(),
                "insufficient_history",
                details
        ));
    }

    private List<BigDecimal> latestValues(List<RiskProvisionalObservationRow> rows) {
        return rows.stream()
                .sorted(Comparator.comparing(
                        RiskProvisionalObservationRow::getTradeDate).reversed())
                .limit(MAX_HISTORY_SAMPLES)
                .map(RiskProvisionalObservationRow::getActualValue)
                .toList();
    }

    private BigDecimal percentile(BigDecimal current, List<BigDecimal> values) {
        long notGreater = values.stream()
                .filter(value -> value.compareTo(current) <= 0)
                .count();
        return BigDecimal.valueOf(notGreater)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal orient(
            String indicatorCode,
            String componentCode,
            BigDecimal percentile
    ) {
        if (RiskIndicatorComponentCatalog.direction(indicatorCode, componentCode)
                == RiskIndicatorComponentCatalog.RiskDirection.DECREASE_IS_RISK) {
            return ONE_HUNDRED.subtract(percentile)
                    .setScale(4, RoundingMode.HALF_UP);
        }
        return percentile.setScale(4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> details(
            LayerTarget layer,
            List<ComponentScore> scores
    ) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provisionalOnly", true);
        List<String> normalizations = scores.stream()
                .map(ComponentScore::normalization)
                .distinct()
                .toList();
        details.put(
                "normalization",
                normalizations.size() == 1 ? normalizations.getFirst() : "mixed"
        );
        details.put(
                "sampleCount",
                scores.stream().mapToInt(ComponentScore::sampleCount).min().orElse(0)
        );
        details.put(
                "partialHistoryReason",
                "正式五年滚动基线尚未满足，仅用于暂定评估"
        );
        Map<String, Object> components = new LinkedHashMap<>();
        for (ComponentScore component : scores) {
            components.put(component.componentCode(), Map.of(
                    "rawValue", component.rawValue(),
                    "score", component.score(),
                    "sampleCount", component.sampleCount(),
                    "normalization", component.normalization()
            ));
        }
        details.put("components", components);
        if (layer.weight().compareTo(BigDecimal.ONE) < 0) {
            details.put("layerObjectType", layer.objectType());
            details.put("layerObjectId", layer.objectId());
            details.put("layerWeight", layer.weight());
        }
        return Map.copyOf(details);
    }

    private BigDecimal average(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private record LayerTarget(String objectType, String objectId, BigDecimal weight) {
    }

    private record ComponentKey(String indicatorCode, String componentCode) {
    }

    private record CrossSectionKey(
            String horizon,
            LocalDate tradeDate,
            LocalDateTime asOf
    ) {
    }

    private record ComponentScore(
            String componentCode,
            BigDecimal rawValue,
            BigDecimal score,
            int sampleCount,
            String normalization,
            RiskProvisionalObservationRow row
    ) {
    }
}
