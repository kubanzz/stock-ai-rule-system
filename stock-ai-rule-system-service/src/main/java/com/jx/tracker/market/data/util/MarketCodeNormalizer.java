package com.jx.tracker.market.data.util;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class MarketCodeNormalizer {

    private MarketCodeNormalizer() {
    }

    public static String toDisplayName(String market) {
        if (market == null) {
            return null;
        }
        String value = market.trim();
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "A股", "CN" -> "A股";
            case "港股", "HK" -> "港股";
            case "美股", "US" -> "美股";
            default -> value;
        };
    }

    public static List<String> aliases(String market) {
        String displayName = toDisplayName(market);
        if (displayName == null) {
            return List.of();
        }
        return switch (displayName) {
            case "A股" -> List.of("A股", "CN");
            case "港股" -> List.of("港股", "HK");
            case "美股" -> List.of("美股", "US");
            default -> List.of(displayName);
        };
    }

    public static boolean equivalent(String first, String second) {
        return Objects.equals(toDisplayName(first), toDisplayName(second));
    }
}
