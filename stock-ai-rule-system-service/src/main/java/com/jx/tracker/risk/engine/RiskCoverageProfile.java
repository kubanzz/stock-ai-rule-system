package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDimension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Defines the indicators expected from one object type under one model version. */
public record RiskCoverageProfile(List<RiskIndicatorDefinition> definitions) {

    public RiskCoverageProfile {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
        if (definitions.isEmpty()) {
            throw new IllegalArgumentException("risk coverage profile must not be empty");
        }
        Set<String> codes = new HashSet<>();
        for (RiskIndicatorDefinition definition : definitions) {
            if (definition == null || !codes.add(definition.code())) {
                throw new IllegalArgumentException(
                        "risk coverage profile definitions must be non-null and unique");
            }
        }
    }

    public int totalWeight() {
        return definitions.stream().mapToInt(RiskIndicatorDefinition::weight).sum();
    }

    public List<RiskIndicatorDefinition> forDimension(RiskDimension dimension) {
        if (dimension == null) {
            throw new IllegalArgumentException("risk dimension must not be null");
        }
        return definitions.stream()
                .filter(definition -> definition.dimension() == dimension)
                .toList();
    }

    public int dimensionWeight(RiskDimension dimension) {
        return forDimension(dimension).stream()
                .mapToInt(RiskIndicatorDefinition::weight)
                .sum();
    }

    public boolean applies(String indicatorCode) {
        return indicatorCode != null && definitions.stream()
                .anyMatch(definition -> definition.code().equals(indicatorCode));
    }

    public boolean applies(RiskDimension dimension) {
        return dimension != null && dimensionWeight(dimension) > 0;
    }
}
