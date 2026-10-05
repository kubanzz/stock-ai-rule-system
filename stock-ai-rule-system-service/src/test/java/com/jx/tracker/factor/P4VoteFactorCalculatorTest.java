package com.jx.tracker.factor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.rule.engine.StockFactorFact;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class P4VoteFactorCalculatorTest {
    private static final String SYMBOL = "600418.SH";
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String P = P4VoteFactorCalculator.PREFIX;
    private final P4VoteFactorCalculator calculator = new P4VoteFactorCalculator();

    @Test
    void frozenScopeHasOneHundredActualMembersAndHash() {
        assertThat(calculator.getSymbols()).hasSize(100).contains(SYMBOL, "603998.SH").doesNotContain("000001.SZ");
        Map<String, Object> result = calculator.calculate("000001.SZ", DATE, stable(), index(.2), metadata());
        assertThat(result).containsEntry(P + "eligible", false).containsEntry(P + "unavailable_reason", "not_in_frozen_pit100_scope");
    }

    @Test
    void unroundedAtomsUseResearchCloseAndPreviousObservedBar() {
        List<StockDailyQuote> stock = declining(.89999, 1.02001);
        Map<String, Object> factors = calculator.calculate(SYMBOL, DATE, stock, index(.200001), metadata());
        assertThat(factors).containsEntry(P + "eligible", true);
        assertThat((double) factors.get(P + "change_pct_5d")).isCloseTo(-.10001, within(1e-12));
        assertThat((double) factors.get(P + "open_gap")).isCloseTo(.02001, within(1e-12));
        StockFactorFact fact = fact(factors);
        assertThat(fact.matches(P + "change_pct_5d", "lte", "-0.10")).isTrue();
        assertThat(fact.matches(P + "open_gap", "gte", "0.02")).isTrue();
        assertThat(fact.matches(P + "index_close_position", "lte", "0.20")).isFalse();
        factors = calculator.calculate(SYMBOL, DATE, declining(.90001, 1.01999), index(.199999), metadata());
        assertThat(fact(factors).matches(P + "change_pct_5d", "lte", "-0.10")).isFalse();
        assertThat(fact(factors).matches(P + "open_gap", "gte", "0.02")).isFalse();
        assertThat(fact(factors).matches(P + "index_close_position", "lte", "0.20")).isTrue();
    }

    @Test
    void missingOrFlatBenchmarkLeavesFirstTwoAtomsAvailable() {
        Map<String, Object> absent = calculator.calculate(SYMBOL, DATE, declining(.89, 1.03), List.of(), metadata());
        assertThat(absent).containsEntry(P + "eligible", true)
                .containsEntry(P + "index_unavailable_reason", "missing_current_benchmark_quote")
                .doesNotContainKey(P + "index_close_position");
        assertThat(fact(absent).matches(P + "change_pct_5d", "lte", "-0.10")).isTrue();
        assertThat(fact(absent).matches(P + "open_gap", "gte", "0.02")).isTrue();
        assertThat(fact(absent).matches(P + "index_close_position", "lte", "0.20")).isFalse();
        List<StockDailyQuote> flat = index(.2);
        flat.getFirst().setHighPrice(new BigDecimal("100"));
        flat.getFirst().setLowPrice(new BigDecimal("100"));
        flat.getFirst().setClosePrice(new BigDecimal("100"));
        assertThat(calculator.calculate(SYMBOL, DATE, declining(.89, 1.03), flat, metadata()))
                .containsEntry(P + "eligible", true).doesNotContainKey(P + "index_close_position");
    }

    @Test
    void futureAndNonTradableBarsDoNotEnterHistory() {
        List<StockDailyQuote> stock = stable();
        Map<String, Object> expected = calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata());
        StockDailyQuote future = bar(DATE.plusDays(1), "900");
        future.setDataSource("mock");
        stock.add(future);
        StockDailyQuote suspended = bar(DATE.minusDays(300), "100");
        suspended.setVolume(BigDecimal.ZERO);
        stock.add(suspended);
        assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata())).isEqualTo(expected);
        stock = stable();
        stock.getLast().setVolume(BigDecimal.ZERO);
        assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata()))
                .containsEntry(P + "eligible", false).containsEntry(P + "unavailable_reason", "missing_current_stock_quote");
    }

    @Test
    void refusesMockAdjustedUnknownSourcesAndInsufficientHistory() {
        for (String source : List.of("mock", "aktools/akshare", "tushare", "csv")) {
            List<StockDailyQuote> stock = stable();
            stock.getFirst().setDataSource(source);
            assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata())).containsEntry(P + "eligible", false);
        }
        List<StockDailyQuote> shortHistory = stable();
        shortHistory.removeFirst();
        assertThat(calculator.calculate(SYMBOL, DATE, shortHistory, index(.2), metadata()))
                .containsEntry(P + "unavailable_reason", "insufficient_stock_history");
        List<StockDailyQuote> trusted = stable();
        trusted.forEach(q -> q.setDataSource(P4VoteFactorCalculator.TUSHARE_RAW_SOURCE));
        List<StockDailyQuote> ix = index(.2);
        ix.forEach(q -> q.setDataSource(P4VoteFactorCalculator.TUSHARE_INDEX_SOURCE));
        assertThat(calculator.calculate(SYMBOL, DATE, trusted, ix, metadata())).containsEntry(P + "eligible", true);
    }

    @Test
    void maintainsNormalStockAndRiskGatesAndDisclosesCurrentStCheck() {
        StockBase st = metadata(); st.setName("*ST江淮");
        assertThat(calculator.calculate(SYMBOL, DATE, stable(), index(.2), st)).containsEntry(P + "eligible", false);
        assertThat(calculator.calculate(SYMBOL, DATE, stable(), index(.2), null)).containsEntry(P + "eligible", false);
        List<StockDailyQuote> stock = stable();stock.getLast().setAmount(new BigDecimal("19999999"));
        assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata()))
                .containsEntry(P + "unavailable_reason", "stock_amount_below_20m");
        stock = stable();stock.forEach(q -> q.setAmount(new BigDecimal("49999999")));
        assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata()))
                .containsEntry(P + "unavailable_reason", "stock_median_amount_below_50m");
        stock = stable();stock.getLast().setVolume(new BigDecimal("2000000"));
        assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata()))
                .containsEntry(P + "risk_status", "high_risk").containsEntry(P + "eligible", false);
        stock = stable();stock.getLast().setClosePrice(new BigDecimal("107.01"));
        assertThat(calculator.calculate(SYMBOL, DATE, stock, index(.2), metadata()))
                .containsEntry(P + "risk_status", "caution").containsEntry(P + "eligible", false);
        assertThat(calculator.calculate(SYMBOL, DATE, stable(), index(.2), metadata()))
                .containsEntry(P + "st_check_basis", "current_stock_base_status_and_name_not_historical_daily_isST")
                .containsEntry(P + "stock_scope_sha256", P4VoteFactorCalculator.STOCK_SCOPE_SHA256);
    }

    @Test
    void reproducesPythonResearchOnFrozenUnadjustedSample() throws Exception {
        JsonNode packet = new ObjectMapper().readTree(new ClassPathResource("p4-vote-001-parity.json").getContentAsByteArray());
        for (JsonNode sample : packet.path("samples")) {
            String symbol = sample.path("symbol").asText();
            LocalDate date = LocalDate.parse(sample.path("signal_date").asText());
            List<StockDailyQuote> stock = csvQuotes(sample.path("stock_csv").asText(), symbol, true);
            List<StockDailyQuote> benchmark = csvQuotes(sample.path("index_csv").asText(), P4VoteFactorCalculator.BENCHMARK_SYMBOL, false);
            StockBase base = metadata();base.setSymbol(symbol);
            Map<String, Object> factors = calculator.calculate(symbol, date, stock, benchmark, base);
            assertThat(factors.get(P + "eligible")).isEqualTo(sample.path("expected").path("eligible").asBoolean());
            for (String field : List.of("change_pct_5d", "open_gap", "index_close_position", "volume_ratio_5d", "volatility_20d", "max_abs_return_20d", "median_amount_20d")) {
                assertThat((double) factors.get(P + field)).as(symbol + "/" + date + "/" + field)
                        .isCloseTo(sample.path("expected").path(field).asDouble(), within("median_amount_20d".equals(field) ? 1e-6 : 1e-10));
            }
        }
    }

    private static List<StockDailyQuote> csvQuotes(String resource, String symbol, boolean stock) throws Exception {
        String[] lines = new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8).split("\\R");
        List<StockDailyQuote> quotes = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] row = lines[i].split(",", -1);
            quotes.add(StockDailyQuote.builder().symbol(symbol).tradeDate(LocalDate.parse(row[0]))
                    .openPrice(new BigDecimal(row[1])).highPrice(new BigDecimal(row[2])).lowPrice(new BigDecimal(row[3]))
                    .closePrice(new BigDecimal(row[4])).volume(new BigDecimal(row[5])).amount(new BigDecimal(row[6]))
                    .dataSource(stock ? P4VoteFactorCalculator.BAOSTOCK_RAW_SOURCE : P4VoteFactorCalculator.BAOSTOCK_INDEX_SOURCE).build());
        }
        return quotes;
    }
    private static StockFactorFact fact(Map<String, Object> factors) {
        return StockFactorFact.from(new RuleExecutionRequest(SYMBOL, DATE, factors, List.of()));
    }
    private static StockBase metadata() {
        return StockBase.builder().symbol(SYMBOL).name("江淮汽车").status("active").dataSource("tushare")
                .lastSyncTime(LocalDateTime.of(2026, 10, 5, 10, 0)).build();
    }
    private static List<StockDailyQuote> stable() {
        List<StockDailyQuote> stock = new ArrayList<>();
        for (int i = 0; i < 252; i++) stock.add(bar(DATE.minusDays(251 - i), "100"));
        return stock;
    }
    private static List<StockDailyQuote> declining(double finalRatio, double openingRatio) {
        List<StockDailyQuote> stock = stable();
        for (int i = 1; i <= 5; i++) {
            stock.get(246 + i).setClosePrice(BigDecimal.valueOf(100 * Math.pow(finalRatio, i / 5.0)));
        }
        stock.getLast().setOpenPrice(BigDecimal.valueOf(stock.get(250).getClosePrice().doubleValue() * openingRatio));
        return stock;
    }
    private static StockDailyQuote bar(LocalDate date, String price) {
        return StockDailyQuote.builder().symbol(SYMBOL).tradeDate(date).openPrice(new BigDecimal(price))
                .closePrice(new BigDecimal(price)).highPrice(new BigDecimal("200")).lowPrice(new BigDecimal("50"))
                .volume(new BigDecimal("1000000")).amount(new BigDecimal("100000000"))
                .dataSource(P4VoteFactorCalculator.BAOSTOCK_RAW_SOURCE).build();
    }
    private static List<StockDailyQuote> index(double position) {
        return new ArrayList<>(List.of(StockDailyQuote.builder().symbol(P4VoteFactorCalculator.BENCHMARK_SYMBOL).tradeDate(DATE)
                .openPrice(new BigDecimal("150")).highPrice(new BigDecimal("200")).lowPrice(new BigDecimal("100"))
                .closePrice(BigDecimal.valueOf(100 + 100 * position)).dataSource(P4VoteFactorCalculator.BAOSTOCK_INDEX_SOURCE).build()));
    }
}
