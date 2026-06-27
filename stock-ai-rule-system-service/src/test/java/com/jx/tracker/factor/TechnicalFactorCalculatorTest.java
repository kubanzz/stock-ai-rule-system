package com.jx.tracker.factor;

import com.jx.tracker.domain.entity.StockDailyQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalFactorCalculatorTest {

    private final TechnicalFactorCalculator calculator = new TechnicalFactorCalculator();

    @Test
    void calculatesMvpTechnicalFactorsWithoutFutureQuotes() {
        LocalDate targetDate = LocalDate.of(2026, 6, 26);
        List<StockDailyQuote> quotes = risingQuotes("000001.SZ", targetDate.minusDays(34), 35);
        quotes.add(quote("000001.SZ", targetDate.plusDays(1), "1.00", "999999"));

        TechnicalFactorResult result = calculator.calculate("000001.SZ", targetDate, quotes);

        Map<String, Object> factors = result.factors();
        assertThat(factors)
                .containsEntry("short_term_trend", "strong_up")
                .containsEntry("volume_status", "normal")
                .containsEntry("technical_status", "bullish")
                .containsEntry("risk_status", "normal")
                .containsEntry("data_status", "normal");
        assertThat((BigDecimal) factors.get("ma5")).isEqualByComparingTo("33.0000");
        assertThat((BigDecimal) factors.get("ma20")).isEqualByComparingTo("25.5000");
        assertThat((BigDecimal) factors.get("rsi14")).isEqualByComparingTo("100.0000");
        assertThat((BigDecimal) factors.get("macd_histogram")).isGreaterThan(BigDecimal.ZERO);
        assertThat((String) factors.get("risk_disclaimer")).contains("辅助决策").contains("不保证收益");
    }

    @Test
    void marksFactorsAsInsufficientWhenHistoryIsTooShort() {
        LocalDate targetDate = LocalDate.of(2026, 6, 26);
        List<StockDailyQuote> quotes = risingQuotes("000001.SZ", targetDate.minusDays(4), 5);

        TechnicalFactorResult result = calculator.calculate("000001.SZ", targetDate, quotes);

        assertThat(result.factors())
                .containsEntry("short_term_trend", "unknown")
                .containsEntry("volume_status", "unknown")
                .containsEntry("technical_status", "insufficient_data")
                .containsEntry("risk_status", "data_insufficient")
                .containsEntry("data_status", "insufficient_data");
    }

    @Test
    void marksFactorsAsSuspendedOrMissingWhenTargetQuoteIsUnavailable() {
        LocalDate targetDate = LocalDate.of(2026, 6, 26);
        List<StockDailyQuote> quotes = risingQuotes("000001.SZ", targetDate.minusDays(10), 10);

        TechnicalFactorResult result = calculator.calculate("000001.SZ", targetDate, quotes);

        assertThat(result.factors())
                .containsEntry("short_term_trend", "unknown")
                .containsEntry("volume_status", "suspended")
                .containsEntry("technical_status", "suspended_or_missing")
                .containsEntry("risk_status", "suspended_or_missing")
                .containsEntry("data_status", "suspended_or_missing");
    }

    private static List<StockDailyQuote> risingQuotes(String symbol, LocalDate startDate, int days) {
        List<StockDailyQuote> quotes = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            BigDecimal close = BigDecimal.valueOf(i + 1);
            quotes.add(quote(symbol, startDate.plusDays(i), close.toPlainString(), "1000000"));
        }
        return quotes;
    }

    private static StockDailyQuote quote(String symbol, LocalDate tradeDate, String close, String volume) {
        BigDecimal closePrice = new BigDecimal(close);
        return StockDailyQuote.builder()
                .symbol(symbol)
                .tradeDate(tradeDate)
                .openPrice(closePrice)
                .highPrice(closePrice)
                .lowPrice(closePrice)
                .closePrice(closePrice)
                .volume(new BigDecimal(volume))
                .changePct(BigDecimal.ONE)
                .build();
    }
}
