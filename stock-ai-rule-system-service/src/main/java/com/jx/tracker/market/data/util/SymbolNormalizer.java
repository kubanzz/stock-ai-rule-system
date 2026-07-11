package com.jx.tracker.market.data.util;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SymbolNormalizer {

    private static final Pattern A_SHARE_WITH_PREFIX = Pattern.compile("^(SZ|SH)(\\d{6})$");
    private static final Pattern A_SHARE_WITH_SUFFIX = Pattern.compile("^(\\d{6})\\.(SZ|SH)$");
    private static final Pattern HK_WITH_SUFFIX = Pattern.compile("^(\\d{5})\\.HK$");
    private static final Pattern US_WITH_SUFFIX = Pattern.compile("^([A-Z][A-Z0-9.-]*)\\.US$");
    private static final Pattern US_PLAIN = Pattern.compile("^[A-Z][A-Z0-9.-]*$");

    private SymbolNormalizer() {
    }

    public static String normalize(String rawSymbol) {
        if (rawSymbol == null) {
            return null;
        }
        String value = rawSymbol.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        var prefixedAShare = A_SHARE_WITH_PREFIX.matcher(value);
        if (prefixedAShare.matches()) {
            return prefixedAShare.group(2) + "." + prefixedAShare.group(1);
        }
        if (A_SHARE_WITH_SUFFIX.matcher(value).matches()
                || HK_WITH_SUFFIX.matcher(value).matches()
                || US_WITH_SUFFIX.matcher(value).matches()) {
            return value;
        }
        if (value.matches("^\\d{5}$")) {
            return value + ".HK";
        }
        if (value.matches("^\\d{6}$")) {
            return value.startsWith("6") ? value + ".SH" : value + ".SZ";
        }
        if (US_PLAIN.matcher(value).matches()) {
            return value + ".US";
        }
        return value;
    }

    public static String parseMarket(String symbol) {
        String normalized = normalize(symbol);
        String exchange = parseExchange(normalized);
        if ("SZ".equals(exchange) || "SH".equals(exchange)) {
            return "CN";
        }
        return exchange;
    }

    public static String parseExchange(String symbol) {
        String normalized = normalize(symbol);
        if (normalized == null) {
            return null;
        }
        int dotIndex = normalized.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == normalized.length() - 1) {
            return null;
        }
        return normalized.substring(dotIndex + 1);
    }
}
