package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskObjectType;

import java.util.Set;

/** Selects the coverage denominator without changing historical model semantics. */
public final class RiskCoverageProfileCatalog {

    public static final String OBJECT_AWARE_MODEL_VERSION = "risk-warning-v2";
    private static final Set<String> V2_SECTOR_CODES = Set.of(
            "V3", "V4", "S1", "S2", "S4",
            "C1", "C3", "C4", "C5", "A3", "A5"
    );
    private static final RiskCoverageProfile FULL =
            new RiskCoverageProfile(RiskIndicatorCatalog.definitions());
    private static final RiskCoverageProfile V2_SECTOR = new RiskCoverageProfile(
            RiskIndicatorCatalog.definitions().stream()
                    .filter(definition -> V2_SECTOR_CODES.contains(definition.code()))
                    .toList()
    );

    private RiskCoverageProfileCatalog() {
    }

    public static RiskCoverageProfile resolve(
            RiskObjectType objectType,
            String modelVersion
    ) {
        if (objectType == null) {
            throw new IllegalArgumentException("risk object type must not be null");
        }
        if (objectType == RiskObjectType.SECTOR
                && OBJECT_AWARE_MODEL_VERSION.equals(trimmed(modelVersion))) {
            return V2_SECTOR;
        }
        return FULL;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
