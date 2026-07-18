package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskIndicatorComponentCatalog;
import com.jx.tracker.risk.engine.RiskNormalizationResult;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskObservation;

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
import java.util.Set;
import java.util.stream.Collectors;

/** 默认以五年（约 1250 个交易日）滚动分位生成 0-100 风险证据。 */
public final class PercentileRiskEvidenceAssembler implements RiskEvidenceAssembler {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final RiskNormalizer normalizer;

    public PercentileRiskEvidenceAssembler(RiskNormalizer normalizer) {
        if (normalizer == null) {
            throw new IllegalArgumentException("normalizer must not be null");
        }
        this.normalizer = normalizer;
    }

    @Override
    public List<RiskEvidence> assemble(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<RiskObservation> observations
    ) {
        Map<ComponentKey, List<RiskObservation>> historyByComponent = observations.stream()
                .filter(observation -> observation.object().equals(object))
                .filter(observation -> observation.horizon() == horizon)
                .filter(observation -> !observation.tradeDate().isAfter(tradeDate))
                .filter(observation -> !observation.availableAt().isAfter(asOf))
                .collect(Collectors.groupingBy(
                        observation -> new ComponentKey(
                                observation.dimension(), observation.indicatorCode(), observation.componentCode()),
                        LinkedHashMap::new, Collectors.toList()));
        Map<ComponentKey, RiskObservation> currentByComponent = new LinkedHashMap<>();
        historyByComponent.forEach((key, series) -> series.stream()
                .filter(observation -> observation.tradeDate().equals(tradeDate))
                .max(Comparator.comparing(RiskObservation::availableAt))
                .ifPresent(current -> currentByComponent.put(key, current)));

        Map<IndicatorKey, Map<String, RiskObservation>> currentByIndicator = new LinkedHashMap<>();
        currentByComponent.forEach((key, current) -> currentByIndicator
                .computeIfAbsent(new IndicatorKey(key.dimension(), key.indicatorCode()), ignored -> new LinkedHashMap<>())
                .put(key.componentCode(), current));
        return currentByIndicator.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(IndicatorKey::indicatorCode)))
                .map(entry -> evidence(entry.getKey(), entry.getValue(), historyByComponent, horizon, asOf))
                .toList();
    }

    private RiskEvidence evidence(
            IndicatorKey indicator,
            Map<String, RiskObservation> currentComponents,
            Map<ComponentKey, List<RiskObservation>> historyByComponent,
            RiskHorizon horizon,
            LocalDateTime asOf
    ) {
        List<String> expectedComponents = RiskIndicatorComponentCatalog.components(indicator.indicatorCode()).stream()
                .map(RiskIndicatorComponentCatalog.ComponentDefinition::code)
                .toList();
        if (expectedComponents.isEmpty()) {
            expectedComponents = List.copyOf(currentComponents.keySet());
        }
        Set<String> missing = new LinkedHashSet<>(expectedComponents);
        missing.removeAll(currentComponents.keySet());
        RiskObservation representative = currentComponents.values().stream()
                .max(Comparator.comparing(RiskObservation::availableAt)).orElseThrow();
        if (!missing.isEmpty()) {
            return unavailableEvidence(representative, RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                    Map.of("normalization", "rolling_percentile", "missingComponents", List.copyOf(missing)));
        }

        List<ComponentScore> components = new ArrayList<>();
        for (String componentCode : expectedComponents) {
            RiskObservation current = currentComponents.get(componentCode);
            if (current.qualityStatus() != RiskDataQualityStatus.AVAILABLE
                    && current.qualityStatus() != RiskDataQualityStatus.VALID_ZERO) {
                return unavailableEvidence(current, current.qualityStatus(),
                        Map.of("normalization", "rolling_percentile", "componentCode", componentCode));
            }
            if (current.qualityStatus() == RiskDataQualityStatus.VALID_ZERO) {
                components.add(new ComponentScore(componentCode, BigDecimal.ZERO, BigDecimal.ZERO,
                        RiskDataQualityStatus.VALID_ZERO, 1, current.source(),
                        current.observedAt(), current.availableAt()));
                continue;
            }
            RiskNormalizationResult normalized = normalizer.rollingPercentile(
                    current.value(), historyByComponent.getOrDefault(
                            new ComponentKey(indicator.dimension(), indicator.indicatorCode(), componentCode), List.of()),
                    horizon, asOf);
            if (normalized.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
                return unavailableEvidence(current, RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                        Map.of("normalization", "rolling_percentile", "componentCode", componentCode,
                                "sampleCount", normalized.sampleCount()));
            }
            BigDecimal oriented = orient(indicator.indicatorCode(), componentCode, normalized.value());
            components.add(new ComponentScore(componentCode, oriented, current.value(),
                    RiskDataQualityStatus.AVAILABLE, normalized.sampleCount(), current.source(),
                    current.observedAt(), current.availableAt()));
        }

        boolean validZero = components.stream().allMatch(
                component -> component.qualityStatus() == RiskDataQualityStatus.VALID_ZERO);
        BigDecimal score = average(components.stream().map(ComponentScore::score).toList());
        BigDecimal rawValue = average(components.stream().map(ComponentScore::rawValue).toList());
        Map<String, Object> details = details(representative, components);
        return new RiskEvidence(
                indicator.dimension(), indicator.indicatorCode(), score, rawValue,
                representative.observedAt(), representative.availableAt(), representative.source(),
                validZero ? RiskDataQualityStatus.VALID_ZERO : RiskDataQualityStatus.AVAILABLE, details);
    }

    private Map<String, Object> details(RiskObservation representative, List<ComponentScore> components) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("normalization", "rolling_percentile");
        details.put("sampleCount", components.stream().mapToInt(ComponentScore::sampleCount).min().orElse(0));
        details.put("tradeDate", representative.tradeDate().toString());
        details.put("extremeCandidate", representative.dimension() == RiskDimension.LOCAL_CONFIRMATION
                || representative.dimension() == RiskDimension.FORCED_SELLING);
        Map<String, Object> componentValues = new LinkedHashMap<>();
        Map<String, String> directions = new LinkedHashMap<>();
        for (ComponentScore component : components) {
            componentValues.put(component.code(), Map.of(
                    "rawValue", component.rawValue(), "score", component.score(),
                    "sampleCount", component.sampleCount(), "source", component.source(),
                    "observedAt", component.observedAt().toString(),
                    "availableAt", component.availableAt().toString()));
            directions.put(component.code(), RiskIndicatorComponentCatalog
                    .direction(representative.indicatorCode(), component.code()).name());
        }
        details.put("components", componentValues);
        details.put("componentDirections", directions);
        if (representative.qualityStatus() == RiskDataQualityStatus.VALID_ZERO) {
            details.put("validZeroAudit", true);
        }
        return Map.copyOf(details);
    }

    private RiskEvidence unavailableEvidence(
            RiskObservation current,
            RiskDataQualityStatus qualityStatus,
            Map<String, Object> details
    ) {
        return new RiskEvidence(
                current.dimension(), current.indicatorCode(), null, null,
                current.observedAt(), current.availableAt(), current.source(), qualityStatus, details);
    }

    private BigDecimal orient(String indicatorCode, String componentCode, BigDecimal percentile) {
        if (RiskIndicatorComponentCatalog.direction(indicatorCode, componentCode)
                == RiskIndicatorComponentCatalog.RiskDirection.DECREASE_IS_RISK) {
            return ONE_HUNDRED.subtract(percentile).setScale(4, RoundingMode.HALF_UP);
        }
        return percentile.setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal average(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private record IndicatorKey(RiskDimension dimension, String indicatorCode) {
    }

    private record ComponentKey(RiskDimension dimension, String indicatorCode, String componentCode) {
    }

    private record ComponentScore(
            String code,
            BigDecimal score,
            BigDecimal rawValue,
            RiskDataQualityStatus qualityStatus,
            int sampleCount,
            String source,
            LocalDateTime observedAt,
            LocalDateTime availableAt
    ) {
    }
}
