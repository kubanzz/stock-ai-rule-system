package com.jx.tracker.config.properties;

import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "stock.dashboard")
public class StockDashboardProperties {

    private static final Map<String, String> DEFAULT_BENCHMARK_SYMBOLS = Map.of(
            "A股", "000300.SH",
            "港股", "HSI.HK",
            "美股", "SPX.US"
    );

    private final Map<String, String> benchmarkSymbols = new LinkedHashMap<>();

    public Map<String, String> getBenchmarkSymbols() {
        return benchmarkSymbols;
    }

    public String benchmarkSymbol(String market) {
        String displayName = MarketCodeNormalizer.toDisplayName(market);
        String configured = benchmarkSymbols.get(market);
        if (configured != null) {
            return configured;
        }
        configured = benchmarkSymbols.entrySet().stream()
                .filter(entry -> MarketCodeNormalizer.equivalent(displayName, entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        return configured != null ? configured : DEFAULT_BENCHMARK_SYMBOLS.get(displayName);
    }
}
