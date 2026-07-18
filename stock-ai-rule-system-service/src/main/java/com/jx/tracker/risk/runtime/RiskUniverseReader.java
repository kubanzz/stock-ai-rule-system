package com.jx.tracker.risk.runtime;

import java.util.List;

@FunctionalInterface
public interface RiskUniverseReader {

    List<String> activeAshareSymbols();
}
