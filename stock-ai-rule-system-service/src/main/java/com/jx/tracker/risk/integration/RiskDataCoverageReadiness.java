package com.jx.tracker.risk.integration;

import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorDefinition;
import com.jx.tracker.risk.model.RiskDimension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 集成线对数据 Provider 静态能力的覆盖关卡；运行时完整度仍由评分引擎逐快照判定。 */
public record RiskDataCoverageReadiness(
        int supportedIndicatorCount,
        int supportedWeight,
        int catalogWeight,
        BigDecimal weightedCoverage,
        Map<RiskDimension, BigDecimal> dimensionCoverage,
        boolean mandatoryEvidenceSupported,
        boolean ready
) {

    private static final BigDecimal MINIMUM_OVERALL_COVERAGE = new BigDecimal("0.8000");
    private static final BigDecimal MINIMUM_DIMENSION_COVERAGE = new BigDecimal("0.6000");

    public RiskDataCoverageReadiness {
        dimensionCoverage = Map.copyOf(dimensionCoverage);
    }

    public static RiskDataCoverageReadiness assess(Set<String> supportedIndicatorCodes) {
        if (supportedIndicatorCodes == null) {
            throw new IllegalArgumentException("supportedIndicatorCodes must not be null");
        }
        Set<String> supported = Set.copyOf(supportedIndicatorCodes);
        Map<String, RiskIndicatorDefinition> catalog = RiskIndicatorCatalog.definitions().stream()
                .collect(Collectors.toUnmodifiableMap(RiskIndicatorDefinition::code, definition -> definition));
        Set<String> unknown = new HashSet<>(supported);
        unknown.removeAll(catalog.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unsupported risk indicator codes: " + unknown);
        }

        int catalogWeight = RiskIndicatorCatalog.definitions().stream()
                .mapToInt(RiskIndicatorDefinition::weight)
                .sum();
        int supportedWeight = supported.stream()
                .map(catalog::get)
                .mapToInt(RiskIndicatorDefinition::weight)
                .sum();
        BigDecimal weightedCoverage = ratio(supportedWeight, catalogWeight);

        Map<RiskDimension, BigDecimal> dimensionCoverage = new EnumMap<>(RiskDimension.class);
        for (RiskDimension dimension : RiskDimension.values()) {
            int dimensionWeight = RiskIndicatorCatalog.forDimension(dimension).stream()
                    .mapToInt(RiskIndicatorDefinition::weight)
                    .sum();
            int supportedDimensionWeight = RiskIndicatorCatalog.forDimension(dimension).stream()
                    .filter(definition -> supported.contains(definition.code()))
                    .mapToInt(RiskIndicatorDefinition::weight)
                    .sum();
            dimensionCoverage.put(dimension, ratio(supportedDimensionWeight, dimensionWeight));
        }

        boolean vSupported = meetsMinimum(dimensionCoverage, RiskDimension.STRUCTURAL_FRAGILITY);
        boolean cSupported = meetsMinimum(dimensionCoverage, RiskDimension.LOCAL_CONFIRMATION);
        boolean aSupported = meetsMinimum(dimensionCoverage, RiskDimension.FORCED_SELLING);
        boolean triggerOrTransmissionSupported =
                meetsMinimum(dimensionCoverage, RiskDimension.SUBSTANTIVE_TRIGGER)
                        || meetsMinimum(dimensionCoverage, RiskDimension.EXTERNAL_TRANSMISSION);
        boolean mandatoryEvidenceSupported = vSupported && cSupported && aSupported
                && triggerOrTransmissionSupported;
        boolean ready = weightedCoverage.compareTo(MINIMUM_OVERALL_COVERAGE) >= 0
                && mandatoryEvidenceSupported;

        return new RiskDataCoverageReadiness(
                supported.size(), supportedWeight, catalogWeight, weightedCoverage,
                dimensionCoverage, mandatoryEvidenceSupported, ready
        );
    }

    private static boolean meetsMinimum(Map<RiskDimension, BigDecimal> coverage, RiskDimension dimension) {
        return coverage.getOrDefault(dimension, BigDecimal.ZERO)
                .compareTo(MINIMUM_DIMENSION_COVERAGE) >= 0;
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            throw new IllegalArgumentException("coverage denominator must be positive");
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
