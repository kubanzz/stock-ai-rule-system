package com.jx.tracker.risk.backfill;

import java.util.ArrayList;
import java.util.List;

public record RiskBackfillPreflight(
        List<String> configurationFailures,
        List<String> environmentFailures,
        String flywayVersion,
        boolean openTradingDay,
        int activeStockCount,
        SourceProbeResult akToolsProbe,
        SourceProbeResult derivedGatewayProbe,
        boolean reportDirectoryWritable,
        long enforcedGateCount
) {

    public RiskBackfillPreflight {
        configurationFailures = configurationFailures == null
                ? List.of() : List.copyOf(configurationFailures);
        environmentFailures = environmentFailures == null
                ? List.of() : List.copyOf(environmentFailures);
        akToolsProbe = akToolsProbe == null
                ? SourceProbeResult.notProbed("aktools") : akToolsProbe;
        derivedGatewayProbe = derivedGatewayProbe == null
                ? SourceProbeResult.notProbed("derived-gateway") : derivedGatewayProbe;
    }

    public boolean configurationReady() {
        return configurationFailures.isEmpty();
    }

    public boolean environmentReady() {
        return environmentFailures.isEmpty();
    }

    public boolean ready() {
        return configurationReady() && environmentReady();
    }

    public List<String> failures() {
        List<String> failures = new ArrayList<>(configurationFailures);
        failures.addAll(environmentFailures);
        return List.copyOf(failures);
    }
}
