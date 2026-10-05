package com.jx.tracker.factor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P4-VOTE-001 的冻结原子与正常股资格。使用截至 T 的有效未复权个股行情，
 * 成交量为股、成交额为元；double 比较前不舍入。指数缺失仅令指数原子不命中。
 * stock_base 的当前状态/名称检查不是研究中的历史逐日 isST，单独记录其来源和局限。
 */
@Component
public class P4VoteFactorCalculator {
    public static final String VERSION = "p4-vote-001-v1";
    public static final String BENCHMARK_SYMBOL = "000300.SH";
    public static final String PREFIX = "p4_vote_001_";
    public static final String STOCK_SCOPE_SHA256 = "fa6b9a06e3ca0f79ccf0a3757961a03e68cc508b7dab770be2ec87102f7c3950";
    public static final String BAOSTOCK_RAW_SOURCE = "BaoStock:query_history_k_data_plus:adjustflag=3";
    public static final String BAOSTOCK_INDEX_SOURCE = "BaoStock:query_history_k_data_plus:sh.000300:adjustflag=3";
    public static final String TUSHARE_RAW_SOURCE = "tushare:daily:unadjusted:shares:yuan";
    public static final String TUSHARE_INDEX_SOURCE = "tushare:index_daily:unadjusted:shares:yuan";
    private static final int MIN_HISTORY = 252;
    private final Set<String> symbols = loadSymbols();

    public Set<String> getSymbols() { return symbols; }
    public boolean isMember(String symbol) { return symbols.contains(symbol); }

    public Map<String, Object> calculate(String symbol, LocalDate date,
                                         List<StockDailyQuote> stockQuotes,
                                         List<StockDailyQuote> benchmarkQuotes,
                                         StockBase currentStock) {
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put(PREFIX + "version", VERSION);
        factors.put(PREFIX + "eligible", false);
        factors.put(PREFIX + "unavailable_reason", "");
        factors.put(PREFIX + "stock_scope_sha256", STOCK_SCOPE_SHA256);
        factors.put(PREFIX + "price_basis", "unadjusted");
        factors.put(PREFIX + "volume_unit", "shares");
        factors.put(PREFIX + "amount_unit", "yuan");
        factors.put(PREFIX + "st_check_basis", "current_stock_base_status_and_name_not_historical_daily_isST");
        factors.put(PREFIX + "st_check_source", currentStock == null || currentStock.getDataSource() == null
                ? "" : currentStock.getDataSource());
        factors.put(PREFIX + "st_check_available_at", currentStock == null || currentStock.getLastSyncTime() == null
                ? "" : currentStock.getLastSyncTime().toString());
        factors.put(PREFIX + "st_check_status", currentStock == null || currentStock.getStatus() == null
                ? "" : currentStock.getStatus());
        factors.put(PREFIX + "st_check_name", currentStock == null || currentStock.getName() == null
                ? "" : currentStock.getName());
        if (date == null) return unavailable(factors, "missing_signal_date");
        if (!isMember(symbol)) return unavailable(factors, "not_in_frozen_pit100_scope");
        if (!symbol.matches("(?:(?:000|001|002|003)\\d{3}\\.SZ|(?:600|601|603|605)\\d{3}\\.SH)")) {
            return unavailable(factors, "not_mainboard");
        }
        Prepared stock = prepare(symbol, date, stockQuotes, true);
        if (stock.failure() != null) return unavailable(factors, stock.failure());
        List<StockDailyQuote> quotes = stock.quotes();
        factors.put(PREFIX + "history_bars", quotes.size());
        if (quotes.isEmpty() || !quotes.getLast().getTradeDate().equals(date)) {
            return unavailable(factors, "missing_current_stock_quote");
        }
        if (quotes.size() < MIN_HISTORY) return unavailable(factors, "insufficient_stock_history");
        int n = quotes.size();
        StockDailyQuote current = quotes.getLast();
        double close = value(current.getClosePrice());
        double priorClose = value(quotes.get(n - 2).getClosePrice());
        double return1d = close / priorClose - 1;
        double change5d = close / value(quotes.get(n - 6).getClosePrice()) - 1;
        double openGap = value(current.getOpenPrice()) / priorClose - 1;
        factors.put(PREFIX + "change_pct_5d", change5d);
        factors.put(PREFIX + "open_gap", openGap);
        factors.put(PREFIX + "stock_quote_source", current.getDataSource());
        double priorVolume = 0;
        for (int i = n - 6; i < n - 1; i++) priorVolume += value(quotes.get(i).getVolume());
        double volumeRatio = value(current.getVolume()) / (priorVolume / 5);
        double[] returns = new double[20];
        double[] amounts = new double[20];
        double maximumAbsoluteReturn = 0;
        for (int i = 0; i < 20; i++) {
            int index = n - 20 + i;
            returns[i] = value(quotes.get(index).getClosePrice()) / value(quotes.get(index - 1).getClosePrice()) - 1;
            amounts[i] = value(quotes.get(index).getAmount());
            maximumAbsoluteReturn = Math.max(maximumAbsoluteReturn, Math.abs(returns[i]));
        }
        java.util.Arrays.sort(amounts);
        double medianAmount = (amounts[9] + amounts[10]) / 2;
        double volatility = populationStd(returns);
        factors.put(PREFIX + "volume_ratio_5d", volumeRatio);
        factors.put(PREFIX + "return_1d", return1d);
        factors.put(PREFIX + "median_amount_20d", medianAmount);
        factors.put(PREFIX + "volatility_20d", volatility);
        factors.put(PREFIX + "max_abs_return_20d", maximumAbsoluteReturn);
        factors.put(PREFIX + "amount", value(current.getAmount()));
        factors.put(PREFIX + "risk_status", volumeRatio >= 2 ? "high_risk"
                : Math.abs(return1d) >= .07 ? "caution" : "normal");
        putIndexAtom(factors, date, benchmarkQuotes);

        if (!currentMetadataSafe(symbol, currentStock)) return unavailable(factors, "current_non_st_metadata_unverified_or_risky");
        if (value(current.getAmount()) < 20_000_000) return unavailable(factors, "stock_amount_below_20m");
        if (medianAmount < 50_000_000) return unavailable(factors, "stock_median_amount_below_50m");
        if (volatility > .04) return unavailable(factors, "stock_volatility_above_4pct");
        if (maximumAbsoluteReturn >= .08) return unavailable(factors, "stock_max_abs_return_at_least_8pct");
        if (!"normal".equals(factors.get(PREFIX + "risk_status"))) return unavailable(factors, "stock_risk_status_not_normal");
        factors.put(PREFIX + "eligible", true);
        return Collections.unmodifiableMap(factors);
    }

    private static void putIndexAtom(Map<String, Object> factors, LocalDate date,
                                     List<StockDailyQuote> benchmarkQuotes) {
        Prepared benchmark = prepare(BENCHMARK_SYMBOL, date, benchmarkQuotes, false);
        if (benchmark.failure() != null) {
            factors.put(PREFIX + "index_unavailable_reason", benchmark.failure());
            return;
        }
        List<StockDailyQuote> quotes = benchmark.quotes();
        if (quotes.isEmpty() || !quotes.getLast().getTradeDate().equals(date)) {
            factors.put(PREFIX + "index_unavailable_reason", "missing_current_benchmark_quote");
            return;
        }
        StockDailyQuote quote = quotes.getLast();
        factors.put(PREFIX + "index_quote_source", quote.getDataSource());
        double range = value(quote.getHighPrice()) - value(quote.getLowPrice());
        if (range <= 0) {
            factors.put(PREFIX + "index_unavailable_reason", "zero_or_negative_benchmark_range");
            return;
        }
        double position = (value(quote.getClosePrice()) - value(quote.getLowPrice())) / range;
        if (!Double.isFinite(position) || position < 0 || position > 1) {
            factors.put(PREFIX + "index_unavailable_reason", "invalid_benchmark_close_position");
            return;
        }
        factors.put(PREFIX + "index_close_position", position);
        factors.put(PREFIX + "index_unavailable_reason", "");
    }

    private static boolean currentMetadataSafe(String symbol, StockBase stock) {
        if (stock == null || !Objects.equals(symbol, stock.getSymbol()) || stock.getName() == null
                || stock.getName().isBlank() || stock.getDataSource() == null || stock.getDataSource().isBlank()
                || stock.getDataSource().toLowerCase(Locale.ROOT).contains("mock")
                || stock.getLastSyncTime() == null || !"active".equalsIgnoreCase(stock.getStatus())) return false;
        String name = stock.getName().toUpperCase(Locale.ROOT);
        return !name.contains("ST") && !name.contains("退");
    }

    private static Prepared prepare(String symbol, LocalDate date, List<StockDailyQuote> source, boolean stock) {
        List<StockDailyQuote> quotes = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        String kind = stock ? "stock" : "benchmark";
        for (StockDailyQuote quote : source == null ? List.<StockDailyQuote>of() : source) {
            if (quote == null || !Objects.equals(symbol, quote.getSymbol()) || quote.getTradeDate() == null
                    || quote.getTradeDate().isAfter(date)) continue;
            if (!dates.add(quote.getTradeDate())) return new Prepared(List.of(), "duplicate_" + kind + "_trade_date");
            String origin = quote.getDataSource();
            if (origin != null && origin.toLowerCase(Locale.ROOT).contains("mock")) {
                return new Prepared(List.of(), "mock_" + kind + "_history");
            }
            boolean trusted = stock ? BAOSTOCK_RAW_SOURCE.equals(origin) || TUSHARE_RAW_SOURCE.equals(origin)
                    : BAOSTOCK_RAW_SOURCE.equals(origin) || BAOSTOCK_INDEX_SOURCE.equals(origin)
                    || "aktools/akshare".equals(origin) || TUSHARE_INDEX_SOURCE.equals(origin);
            if (!trusted) return new Prepared(List.of(), "unverified_unadjusted_" + kind + "_source");
            if (positive(quote.getOpenPrice()) && positive(quote.getHighPrice()) && positive(quote.getLowPrice())
                    && positive(quote.getClosePrice()) && (!stock || positive(quote.getVolume()) && positive(quote.getAmount()))) {
                quotes.add(quote);
            }
        }
        quotes.sort(Comparator.comparing(StockDailyQuote::getTradeDate));
        return new Prepared(quotes, null);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && Double.isFinite(value.doubleValue());
    }
    private static double value(BigDecimal value) { return value.doubleValue(); }
    private static double populationStd(double[] values) {
        double mean = java.util.Arrays.stream(values).average().orElse(0);
        double sum = 0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / values.length);
    }
    private static Map<String, Object> unavailable(Map<String, Object> factors, String reason) {
        factors.put(PREFIX + "unavailable_reason", reason);
        return Collections.unmodifiableMap(factors);
    }
    private static Set<String> loadSymbols() {
        try {
            byte[] assignment = new ClassPathResource("rules/p4-vote-001-pit-assignment.csv").getContentAsByteArray();
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(assignment));
            if (!STOCK_SCOPE_SHA256.equals(hash)) throw new IllegalStateException("P4 PIT100 assignment hash changed");
            JsonNode definition = new ObjectMapper().readTree(new ClassPathResource("rules/p4-vote-001.json").getContentAsByteArray());
            if (!STOCK_SCOPE_SHA256.equals(definition.path("stock_scope_sha256").asText())) {
                throw new IllegalStateException("P4 PIT100 resource hash changed");
            }
            Set<String> assigned = new HashSet<>();
            String[] lines = new String(assignment, java.nio.charset.StandardCharsets.UTF_8).split("\\R");
            for (int i = 1; i < lines.length; i++) if (!lines[i].isBlank()) assigned.add(lines[i].split(",", -1)[0]);
            Set<String> registered = new HashSet<>();
            definition.path("symbols").forEach(node -> registered.add(node.asText()));
            if (assigned.size() != 100 || !assigned.equals(registered)) throw new IllegalStateException("P4 PIT100 symbols changed");
            return Collections.unmodifiableSet(assigned);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("P4 frozen scope cannot be loaded", e);
        }
    }
    private record Prepared(List<StockDailyQuote> quotes, String failure) { }
}
