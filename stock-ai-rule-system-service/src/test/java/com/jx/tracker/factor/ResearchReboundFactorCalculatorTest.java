package com.jx.tracker.factor;

import com.jx.tracker.domain.entity.StockDailyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class ResearchReboundFactorCalculatorTest {

    private static final String SYMBOL = "000062.SZ";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 29);
    private final ResearchReboundFactorCalculator calculator = new ResearchReboundFactorCalculator();

    @Test
    void usesExactlyTheFrozen125StockUniverse() {
        assertThat(calculator.getSymbols()).hasSize(125).contains(SYMBOL, "000301.SZ");
        assertThat(calculator.isMember("000001.SZ")).isFalse();
        assertThat(calculator.isMember(null)).isFalse();
        assertThatThrownBy(() -> calculator.getSymbols().add("000001.SZ"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(calculator.calculate("000001.SZ", DATE, List.of(), List.of()))
                .containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "not_in_fixed_sz125_universe");
    }

    @Test
    void counts252ValidObservedBarsAndAllowsZeroVolumeBenchmark() {
        Map<String, Object> factors = calculate(stockQuotes(252), benchmarkQuotes(60));
        assertThat(factors).containsEntry("research_rebound_version", "g144-g118-v1")
                .containsEntry("research_rebound_eligible", true)
                .containsEntry("research_rebound_unavailable_reason", "")
                .containsEntry("research_history_bars", 252)
                .containsEntry("research_rsi14", 100.0)
                .containsEntry("research_volume_ratio_5d", 1.0)
                .containsEntry("research_volatility_20d", 0.0);
        assertThat(calculate(stockQuotes(251), benchmarkQuotes(60)))
                .containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "insufficient_stock_history");
        assertThat(calculate(stockQuotes(252), benchmarkQuotes(59)))
                .containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "insufficient_benchmark_history");
    }

    @Test
    void ignoresFutureAndOtherSymbolInputsBeforeCheckingDataQuality() {
        List<StockDailyQuote> stock = stockQuotes(252);
        List<StockDailyQuote> benchmark = benchmarkQuotes(60);
        Map<String, Object> expected = calculate(stock, benchmark);
        StockDailyQuote future = quote(SYMBOL, DATE.plusDays(1), 1);
        future.setDataSource("mock");
        stock.add(future);
        stock.add(quote("600000.SH", DATE, 1));
        StockDailyQuote futureIndex = quote("000300.SH", DATE.plusDays(1), 1);
        futureIndex.setDataSource("mock");
        benchmark.add(futureIndex);
        assertThat(calculate(stock, benchmark)).isEqualTo(expected);
    }

    @Test
    void requiresCurrentStockAndBenchmarkDatesWithoutUsingStaleRows() {
        List<StockDailyQuote> stock = stockQuotes(253);
        stock.removeLast();
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "missing_current_stock_quote");
        List<StockDailyQuote> benchmark = benchmarkQuotes(61);
        benchmark.removeLast();
        assertThat(calculate(stockQuotes(252), benchmark))
                .containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "missing_current_benchmark_quote");
    }

    @Test
    void excludesInvalidObservedStockRowsButRejectsCurrentInvalidQuote() {
        List<StockDailyQuote> stock = stockQuotes(253);
        stock.getFirst().setAmount(BigDecimal.ZERO);
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_eligible", true)
                .containsEntry("research_history_bars", 252);
        stock.getLast().setVolume(BigDecimal.ZERO);
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "missing_current_stock_quote");
    }

    @Test
    void rejectsAnyRelevantMockHistoryOrDuplicateDates() {
        List<StockDailyQuote> stock = stockQuotes(252);
        stock.getFirst().setDataSource("MoCk");
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "mock_stock_history");
        List<StockDailyQuote> benchmark = benchmarkQuotes(60);
        benchmark.getFirst().setDataSource("mock");
        assertThat(calculate(stockQuotes(252), benchmark))
                .containsEntry("research_rebound_unavailable_reason", "mock_benchmark_history");
        stock = stockQuotes(252);
        stock.add(stock.getFirst());
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "duplicate_stock_trade_date");
    }

    @Test
    void usesPriorFiveVolumesAndDoesNotRoundValuesBeforeThresholds() {
        List<StockDailyQuote> stock = stockQuotes(252);
        stock.getLast().setVolume(new BigDecimal("1999999"));
        Map<String, Object> factors = calculate(stock, benchmarkQuotes(60));
        assertThat(number(factors, "volume_ratio_5d")).isCloseTo(1.999999, offset(1e-12));
        assertThat(factors).containsEntry("research_rebound_eligible", true);
        stock.getLast().setVolume(new BigDecimal("2000000"));
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_eligible", false)
                .containsEntry("research_rebound_unavailable_reason", "stock_volume_ratio_at_least_2");
    }

    @Test
    void computesPopulationVolatilityOnTwentyReturnsIncludingToday() {
        List<StockDailyQuote> stock = stockQuotes(252);
        StockDailyQuote current = stock.getLast();
        current.setClosePrice(new BigDecimal("97"));
        current.setLowPrice(new BigDecimal("96"));
        Map<String, Object> factors = calculate(stock, benchmarkQuotes(60));
        double drop = 97.0 / 100 - 1;
        assertThat(number(factors, "return_1d")).isEqualTo(drop);
        assertThat(number(factors, "intraday_return")).isEqualTo(drop);
        assertThat(number(factors, "volatility_20d"))
                .isCloseTo(Math.abs(drop) * Math.sqrt(19) / 20, offset(1e-14));
        assertThat(number(factors, "max_abs_return_20d")).isEqualTo(Math.abs(drop));
        assertThat(number(factors, "close_position")).isEqualTo(.2);
        assertThat(number(factors, "rsi14")).isEqualTo(0);
    }

    @Test
    void computesRollingFourteenRsiRatherThanWilderSmoothing() {
        List<StockDailyQuote> stock = stockQuotes(252);
        stock.get(stock.size() - 15).setClosePrice(new BigDecimal("98"));
        stock.get(stock.size() - 14).setClosePrice(new BigDecimal("99"));
        stock.getLast().setClosePrice(new BigDecimal("99"));
        Map<String, Object> factors = calculate(stock, benchmarkQuotes(60));
        // 14 differences contain +1, +1, eleven zeroes and -1; the earlier -2 is excluded.
        assertThat(number(factors, "rsi14")).isCloseTo(100 - 100.0 / 3, offset(1e-12));
    }

    @Test
    void computesTwentyDayAmountMedianIncludingTodayAndInclusiveMinimumAmounts() {
        List<StockDailyQuote> stock = stockQuotes(252);
        for (int i = stock.size() - 20; i < stock.size(); i++) {
            stock.get(i).setAmount(new BigDecimal("50000000"));
        }
        stock.getLast().setAmount(new BigDecimal("20000000"));
        Map<String, Object> factors = calculate(stock, benchmarkQuotes(60));
        assertThat(factors).containsEntry("research_rebound_eligible", true);
        assertThat(number(factors, "median_amount_20d")).isEqualTo(50_000_000);
        stock.getLast().setAmount(new BigDecimal("19999999"));
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "stock_amount_below_20m");
        // Ten 40m and ten 60m observations must produce the arithmetic median 50m.
        for (int i = stock.size() - 20; i < stock.size(); i++) {
            stock.get(i).setAmount(BigDecimal.valueOf(i < stock.size() - 10 ? 40_000_000 : 60_000_000));
        }
        assertThat(number(calculate(stock, benchmarkQuotes(60)), "median_amount_20d")).isEqualTo(50_000_000);
        stock.getLast().setAmount(new BigDecimal("40000000"));
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "stock_median_amount_below_50m");
    }

    @Test
    void usesAdjustedCloseReturnForRiskRatherThanSupplierChangePct() {
        List<StockDailyQuote> stock = stockQuotes(252);
        stock.getLast().setChangePct(new BigDecimal("100"));
        assertThat(calculate(stock, benchmarkQuotes(60))).containsEntry("research_rebound_eligible", true);
        stock.getLast().setChangePct(null);
        stock.getLast().setClosePrice(new BigDecimal("107"));
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "stock_abs_return_at_least_7pct");
        // An earlier 8% move is part of the 20-return risk gate, even when T is quiet.
        stock = stockQuotes(252);
        stock.get(stock.size() - 2).setClosePrice(new BigDecimal("108"));
        assertThat(calculate(stock, benchmarkQuotes(60)))
                .containsEntry("research_rebound_unavailable_reason", "stock_max_abs_return_at_least_8pct");
    }

    @Test
    void calculatesIndexMovingAveragesIncludingTAndDoesNotInventZeroRangePosition() {
        List<StockDailyQuote> benchmark = benchmarkQuotes(60);
        for (int i = 0; i < benchmark.size(); i++) {
            benchmark.get(i).setClosePrice(BigDecimal.valueOf(3000 + i));
        }
        StockDailyQuote current = benchmark.getLast();
        current.setLowPrice(BigDecimal.valueOf(3050));
        current.setHighPrice(BigDecimal.valueOf(3060));
        Map<String, Object> factors = calculate(stockQuotes(252), benchmark);
        assertThat(number(factors, "hs300_distance_ma20"))
                .isCloseTo(3059 / 3049.5 - 1, offset(1e-14));
        assertThat(number(factors, "hs300_distance_ma60"))
                .isCloseTo(3059 / 3029.5 - 1, offset(1e-14));
        assertThat(number(factors, "hs300_close_position")).isEqualTo(.9);
        current.setLowPrice(current.getClosePrice());
        current.setHighPrice(current.getClosePrice());
        assertThat(calculate(stockQuotes(252), benchmark)).doesNotContainKey("research_hs300_close_position");
    }

    private Map<String, Object> calculate(List<StockDailyQuote> stock, List<StockDailyQuote> benchmark) {
        return calculator.calculate(SYMBOL, DATE, stock, benchmark);
    }

    private static double number(Map<String, Object> factors, String suffix) {
        return ((Number) factors.get("research_" + suffix)).doubleValue();
    }

    private static List<StockDailyQuote> stockQuotes(int size) {
        List<StockDailyQuote> quotes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            quotes.add(quote(SYMBOL, DATE.minusDays(size - i - 1), 100));
        }
        return quotes;
    }

    private static List<StockDailyQuote> benchmarkQuotes(int size) {
        List<StockDailyQuote> quotes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            StockDailyQuote quote = quote("000300.SH", DATE.minusDays(size - i - 1), 3000);
            quote.setVolume(BigDecimal.ZERO);
            quote.setAmount(BigDecimal.ZERO);
            quotes.add(quote);
        }
        return quotes;
    }

    private static StockDailyQuote quote(String symbol, LocalDate date, double close) {
        return StockDailyQuote.builder().symbol(symbol).tradeDate(date)
                .openPrice(BigDecimal.valueOf(close)).closePrice(BigDecimal.valueOf(close))
                .highPrice(BigDecimal.valueOf(close + 1)).lowPrice(BigDecimal.valueOf(close - 1))
                .volume(BigDecimal.valueOf(1_000_000)).amount(BigDecimal.valueOf(100_000_000))
                .changePct(BigDecimal.ZERO).dataSource("aktools").build();
    }
}
