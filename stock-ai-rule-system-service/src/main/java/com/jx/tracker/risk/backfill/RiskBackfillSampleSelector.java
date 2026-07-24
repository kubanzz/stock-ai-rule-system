package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.data.market.AshareRiskObjectCatalog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RiskBackfillSampleSelector {

    private final AshareRiskObjectCatalog objectCatalog = new AshareRiskObjectCatalog();

    public List<String> select(
            List<String> activeSymbols,
            List<String> requestedSymbols,
            int sampleSize
    ) {
        if (sampleSize < 1 || sampleSize > 500) {
            throw new IllegalArgumentException("sampleSize must be between 1 and 500");
        }
        List<String> universe = normalize(activeSymbols);
        if (universe.isEmpty()) {
            throw new IllegalStateException("active A-share universe must not be empty");
        }
        List<String> requested = normalize(requestedSymbols);
        if (!requested.isEmpty()) {
            Set<String> active = Set.copyOf(universe);
            List<String> unknown = requested.stream()
                    .filter(symbol -> !active.contains(symbol))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "requested symbols must belong to the active A-share universe: " + unknown);
            }
            return requested;
        }
        if (universe.size() <= sampleSize) {
            return universe;
        }
        if (sampleSize == 1) {
            return List.of(universe.getFirst());
        }
        List<String> sample = new ArrayList<>(sampleSize);
        for (int position = 0; position < sampleSize; position++) {
            int index = (int) Math.round(
                    (double) position * (universe.size() - 1) / (sampleSize - 1));
            sample.add(universe.get(index));
        }
        return List.copyOf(sample);
    }

    private List<String> normalize(List<String> symbols) {
        if (symbols == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        symbols.stream()
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .map(objectCatalog::stock)
                .map(object -> object.objectId())
                .sorted()
                .forEach(normalized::add);
        return List.copyOf(normalized);
    }
}
