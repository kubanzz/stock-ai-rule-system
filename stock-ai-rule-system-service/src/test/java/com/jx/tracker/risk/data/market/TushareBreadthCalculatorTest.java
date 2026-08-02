package com.jx.tracker.risk.data.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TushareBreadthCalculatorTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);

    @Test
    void calculatesCurrentInclusiveHighLowAndFiftyBarMovingAverage() {
        List<TushareBreadthCalculator.DailyBar> bars = new ArrayList<>();
        IntStream.range(0, 252).forEach(index -> {
            LocalDate date = START.plusDays(index);
            bars.add(bar(
                    "000001.SZ", date,
                    BigDecimal.valueOf(index + 1L),
                    BigDecimal.valueOf(index)));
            bars.add(bar(
                    "600519.SH", date,
                    BigDecimal.valueOf(252L - index),
                    BigDecimal.valueOf(253L - index)));
        });

        TushareBreadthCalculator.Calculation result =
                new TushareBreadthCalculator().calculate(
                        bars, START.plusDays(251));

        assertThat(result.points()).singleElement().satisfies(point -> {
            assertThat(point.tradeDate()).isEqualTo(START.plusDays(251));
            assertThat(point.advancingCount()).isEqualTo(1);
            assertThat(point.decliningCount()).isEqualTo(1);
            assertThat(point.newHighCount()).isEqualTo(1);
            assertThat(point.newLowCount()).isEqualTo(1);
            assertThat(point.aboveMovingAverageCount()).isEqualTo(1);
            assertThat(point.actualCount()).isEqualTo(2);
            assertThat(point.eligibleCount()).isEqualTo(2);
            assertThat(point.coverage()).isEqualByComparingTo("1.0000");
            assertThat(point.formal()).isTrue();
        });
    }

    @Test
    void treatsExactlyNineteenOfTwentyEligibleStocksAsFormalCoverage() {
        TushareBreadthCalculator.Calculation result =
                new TushareBreadthCalculator().calculate(
                        coverageBars(19), START.plusDays(251));

        assertThat(result.points()).singleElement().satisfies(point -> {
            assertThat(point.actualCount()).isEqualTo(20);
            assertThat(point.eligibleCount()).isEqualTo(19);
            assertThat(point.coverage()).isEqualByComparingTo("0.9500");
            assertThat(point.formal()).isTrue();
        });
    }

    @Test
    void marksCoverageBelowNinetyFivePercentAsPartialWithoutFakeZeros() {
        TushareBreadthCalculator.Calculation result =
                new TushareBreadthCalculator().calculate(
                        coverageBars(18), START.plusDays(251));

        assertThat(result.points()).singleElement().satisfies(point -> {
            assertThat(point.actualCount()).isEqualTo(20);
            assertThat(point.eligibleCount()).isEqualTo(18);
            assertThat(point.coverage()).isEqualByComparingTo("0.9000");
            assertThat(point.eligibleCount()).isPositive();
            assertThat(point.formal()).isFalse();
        });
    }

    @Test
    void ingestsWarmupBarsButOnlyReturnsDatesAtOrAfterResultStart() {
        List<TushareBreadthCalculator.DailyBar> bars = new ArrayList<>();
        IntStream.range(0, 253).forEach(index -> bars.add(bar(
                "000001.SZ",
                START.plusDays(index),
                BigDecimal.valueOf(index + 1L),
                BigDecimal.valueOf(index))));

        TushareBreadthCalculator.Calculation result =
                new TushareBreadthCalculator().calculate(
                        bars, START.plusDays(252));

        assertThat(result.points()).singleElement()
                .extracting(TushareBreadthCalculator.BreadthCounts::tradeDate)
                .isEqualTo(START.plusDays(252));
    }

    private static List<TushareBreadthCalculator.DailyBar> coverageBars(
            int eligibleCount
    ) {
        List<TushareBreadthCalculator.DailyBar> bars = new ArrayList<>();
        IntStream.range(0, eligibleCount).forEach(symbolIndex ->
                IntStream.range(0, 252).forEach(dayIndex -> bars.add(bar(
                        String.format("%06d.SZ", symbolIndex + 1),
                        START.plusDays(dayIndex),
                        BigDecimal.valueOf(dayIndex + 1L),
                        BigDecimal.valueOf(dayIndex)))));
        IntStream.range(eligibleCount, 20).forEach(symbolIndex -> bars.add(bar(
                String.format("%06d.SZ", symbolIndex + 1),
                START.plusDays(251),
                BigDecimal.ONE,
                BigDecimal.ONE)));
        return bars;
    }

    private static TushareBreadthCalculator.DailyBar bar(
            String code,
            LocalDate tradeDate,
            BigDecimal close,
            BigDecimal previousClose
    ) {
        return new TushareBreadthCalculator.DailyBar(
                code, tradeDate, close, previousClose);
    }
}
