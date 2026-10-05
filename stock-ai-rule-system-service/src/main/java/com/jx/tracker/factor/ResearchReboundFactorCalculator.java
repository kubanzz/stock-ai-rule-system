package com.jx.tracker.factor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.StockDailyQuote;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * G144/G118 冻结研究规则的独立因子计算，不改变现有技术因子的四位小数口径。
 *
 * <p>调用者必须提供同一复权基准的前复权个股 OHLC、原始成交量/成交额，以及
 * 未复权沪深300 OHLC。StockDailyQuote 没有复权元数据，本类不能验证或修复该契约。
 * 窗口按截至 T 的有效观测行计数，与研究相同；不填补停牌，也不读取 T 后行情。
 * 全部数值使用 double 原始精度，不在规则比较前舍入。</p>
 */
@Component
public class ResearchReboundFactorCalculator {

    public static final String VERSION = "g144-g118-v1";
    public static final String BENCHMARK_SYMBOL = "000300.SH";
    public static final int MIN_STOCK_HISTORY = 252;
    public static final int MIN_BENCHMARK_HISTORY = 60;
    private static final String PREFIX = "research_";
    private final Set<String> symbols;

    public ResearchReboundFactorCalculator() {
        this.symbols = loadSymbols();
    }

    public Set<String> getSymbols() {
        return symbols;
    }

    public boolean isMember(String symbol) {
        return symbols.contains(symbol);
    }

    public Map<String, Object> calculate(String symbol, LocalDate date,
                                         List<StockDailyQuote> stockQuotes,
                                         List<StockDailyQuote> benchmarkQuotes) {
        Map<String, Object> factors = new LinkedHashMap<>();
        factors.put("research_rebound_version", VERSION);
        factors.put("research_rebound_eligible", false);
        factors.put("research_rebound_unavailable_reason", "");
        if (date == null) {
            return unavailable(factors, "missing_signal_date");
        }
        if (!isMember(symbol)) {
            return unavailable(factors, "not_in_fixed_sz125_universe");
        }
        Prepared stock = prepare(symbol, date, stockQuotes, true);
        if (stock.failure() != null) {
            return unavailable(factors, stock.failure());
        }
        List<StockDailyQuote> quotes = stock.quotes();
        factors.put(PREFIX + "history_bars", quotes.size());
        if (!hasCurrent(quotes, date)) {
            return unavailable(factors, "missing_current_stock_quote");
        }
        if (quotes.size() < MIN_STOCK_HISTORY) {
            return unavailable(factors, "insufficient_stock_history");
        }
        StockDailyQuote current = quotes.getLast();
        int n = quotes.size();
        double close = value(current.getClosePrice());
        double return1d = close / value(quotes.get(n - 2).getClosePrice()) - 1;
        double priorVolume = 0;
        for (int i = n - 6; i < n - 1; i++) {
            priorVolume += value(quotes.get(i).getVolume());
        }
        double volumeRatio = value(current.getVolume()) / (priorVolume / 5);
        double[] returns = new double[20];
        double[] amounts = new double[20];
        double maximumAbsoluteReturn = 0;
        for (int i = 0; i < 20; i++) {
            int index = n - 20 + i;
            returns[i] = value(quotes.get(index).getClosePrice())
                    / value(quotes.get(index - 1).getClosePrice()) - 1;
            amounts[i] = value(quotes.get(index).getAmount());
            maximumAbsoluteReturn = Math.max(maximumAbsoluteReturn, Math.abs(returns[i]));
        }
        java.util.Arrays.sort(amounts);
        double medianAmount = (amounts[9] + amounts[10]) / 2;
        double volatility = populationStd(returns);
        double amount = value(current.getAmount());
        factors.put(PREFIX + "rsi14", rsi14(quotes));
        factors.put(PREFIX + "volume_ratio_5d", volumeRatio);
        putClosePosition(factors, PREFIX + "close_position", current);
        factors.put(PREFIX + "return_1d", return1d);
        factors.put(PREFIX + "intraday_return", close / value(current.getOpenPrice()) - 1);
        factors.put(PREFIX + "volatility_20d", volatility);
        factors.put(PREFIX + "median_amount_20d", medianAmount);
        factors.put(PREFIX + "max_abs_return_20d", maximumAbsoluteReturn);
        factors.put(PREFIX + "amount", amount);

        Prepared benchmark = prepare(BENCHMARK_SYMBOL, date, benchmarkQuotes, false);
        if (benchmark.failure() != null) {
            return unavailable(factors, benchmark.failure());
        }
        List<StockDailyQuote> indexQuotes = benchmark.quotes();
        if (!hasCurrent(indexQuotes, date)) {
            return unavailable(factors, "missing_current_benchmark_quote");
        }
        if (indexQuotes.size() < MIN_BENCHMARK_HISTORY) {
            return unavailable(factors, "insufficient_benchmark_history");
        }
        StockDailyQuote indexCurrent = indexQuotes.getLast();
        putClosePosition(factors, PREFIX + "hs300_close_position", indexCurrent);
        factors.put(PREFIX + "hs300_distance_ma20", value(indexCurrent.getClosePrice())
                / meanClose(indexQuotes, 20) - 1);
        factors.put(PREFIX + "hs300_distance_ma60", value(indexCurrent.getClosePrice())
                / meanClose(indexQuotes, 60) - 1);

        if (amount < 20_000_000) {
            return unavailable(factors, "stock_amount_below_20m");
        }
        if (medianAmount < 50_000_000) {
            return unavailable(factors, "stock_median_amount_below_50m");
        }
        if (volatility > .04) {
            return unavailable(factors, "stock_volatility_above_4pct");
        }
        if (maximumAbsoluteReturn >= .08) {
            return unavailable(factors, "stock_max_abs_return_at_least_8pct");
        }
        // 研究 overbought 必须同时有 volume_ratio >= 2，已包含在该过滤内。
        if (volumeRatio >= 2) {
            return unavailable(factors, "stock_volume_ratio_at_least_2");
        }
        if (Math.abs(return1d) >= .07) {
            return unavailable(factors, "stock_abs_return_at_least_7pct");
        }
        factors.put("research_rebound_eligible", true);
        return Collections.unmodifiableMap(factors);
    }

    private static Prepared prepare(String symbol, LocalDate date,
                                    List<StockDailyQuote> source, boolean stock) {
        String kind = stock ? "stock" : "benchmark";
        List<StockDailyQuote> quotes = new ArrayList<>();
        Set<LocalDate> dates = new HashSet<>();
        for (StockDailyQuote quote : source == null ? List.<StockDailyQuote>of() : source) {
            if (quote == null || !Objects.equals(symbol, quote.getSymbol())
                    || quote.getTradeDate() == null || quote.getTradeDate().isAfter(date)) {
                continue;
            }
            if ("mock".equalsIgnoreCase(quote.getDataSource())) {
                return new Prepared(List.of(), "mock_" + kind + "_history");
            }
            if (!dates.add(quote.getTradeDate())) {
                return new Prepared(List.of(), "duplicate_" + kind + "_trade_date");
            }
            if (positive(quote.getOpenPrice()) && positive(quote.getHighPrice())
                    && positive(quote.getLowPrice()) && positive(quote.getClosePrice())
                    && (!stock || positive(quote.getVolume()) && positive(quote.getAmount()))) {
                quotes.add(quote);
            }
        }
        quotes.sort(Comparator.comparing(StockDailyQuote::getTradeDate));
        return new Prepared(quotes, null);
    }

    private static boolean hasCurrent(List<StockDailyQuote> quotes, LocalDate date) {
        return !quotes.isEmpty() && quotes.getLast().getTradeDate().equals(date);
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0 && Double.isFinite(value.doubleValue());
    }

    private static double value(BigDecimal value) {
        return value.doubleValue();
    }

    private static double rsi14(List<StockDailyQuote> quotes) {
        double gains = 0;
        double losses = 0;
        for (int i = quotes.size() - 14; i < quotes.size(); i++) {
            double difference = value(quotes.get(i).getClosePrice())
                    - value(quotes.get(i - 1).getClosePrice());
            gains += Math.max(difference, 0);
            losses += Math.max(-difference, 0);
        }
        return losses == 0 ? 100 : 100 - 100 / (1 + gains / losses);
    }

    private static double populationStd(double[] values) {
        double mean = 0;
        for (double value : values) {
            mean += value;
        }
        mean /= values.length;
        double variance = 0;
        for (double value : values) {
            variance += (value - mean) * (value - mean);
        }
        return Math.sqrt(variance / values.length);
    }

    private static double meanClose(List<StockDailyQuote> quotes, int days) {
        double sum = 0;
        for (int i = quotes.size() - days; i < quotes.size(); i++) {
            sum += value(quotes.get(i).getClosePrice());
        }
        return sum / days;
    }

    private static void putClosePosition(Map<String, Object> factors, String key, StockDailyQuote quote) {
        double range = value(quote.getHighPrice()) - value(quote.getLowPrice());
        // 研究的零振幅为 NaN：不把它当成收在底部或顶部。
        if (range != 0) {
            factors.put(key, (value(quote.getClosePrice()) - value(quote.getLowPrice())) / range);
        }
    }

    private static Map<String, Object> unavailable(Map<String, Object> factors, String reason) {
        factors.put("research_rebound_unavailable_reason", reason);
        return Collections.unmodifiableMap(factors);
    }

    private static Set<String> loadSymbols() {
        try (InputStream input = new ClassPathResource("rules/research-rebound-sz125.json").getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(input);
            if (!VERSION.equals(root.path("version").asText()) || !root.path("symbols").isArray()) {
                throw new IllegalStateException("研究反弹股票名单版本或格式无效");
            }
            Set<String> result = new LinkedHashSet<>();
            for (JsonNode symbol : root.path("symbols")) {
                if (!symbol.isTextual() || !symbol.asText().matches("(?:000|001|002|003)\\d{3}\\.SZ")
                        || !result.add(symbol.asText())) {
                    throw new IllegalStateException("研究反弹股票名单包含无效或重复成员");
                }
            }
            if (result.size() != 125) {
                throw new IllegalStateException("研究反弹股票名单必须包含125只原始成员");
            }
            return Collections.unmodifiableSet(result);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取研究反弹股票名单", e);
        }
    }

    private record Prepared(List<StockDailyQuote> quotes, String failure) {
    }
}
