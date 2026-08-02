package com.jx.tracker.risk.data.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TushareCrossMarketCalculatorTest {

    private static final LocalDate LAST_CN_SESSION =
            LocalDate.of(2026, 3, 30);

    @Test
    void alignsCompletedClosesWithoutUsingUnitedStatesSameDayData() {
        Fixture fixture = correlatedFixture();

        TushareCrossMarketCalculator.Calculation result =
                new TushareCrossMarketCalculator().calculate(
                        fixture.cnOpenDates(),
                        fixture.benchmarkCloses(),
                        fixture.globalCloses(),
                        LAST_CN_SESSION);

        assertThat(result.gaps()).isEmpty();
        assertThat(result.points()).singleElement().satisfies(point -> {
            assertThat(point.tradeDate()).isEqualTo(LAST_CN_SESSION);
            assertThat(point.leadingAssetReturn()).isEqualByComparingTo("-0.0050000000");
            assertThat(point.dynamicCorrelation()).isEqualByComparingTo("1.0000000000");
            assertThat(point.confirmedDownMarketCount()).isEqualTo(4);
            assertThat(point.observedMarketCount()).isEqualTo(4);
            assertThat(point.selectedCloseDates())
                    .containsEntry("SPX", LocalDate.of(2026, 3, 27))
                    .containsEntry("IXIC", LocalDate.of(2026, 3, 27))
                    .containsEntry("HSI", LAST_CN_SESSION)
                    .containsEntry("N225", LAST_CN_SESSION);
        });
    }

    @Test
    void refusesToProduceAFormalPointWhenOneMarketIsUnavailable() {
        Fixture fixture = correlatedFixture();
        Map<String, Map<LocalDate, BigDecimal>> incomplete =
                new LinkedHashMap<>(fixture.globalCloses());
        incomplete.put("SPX", Map.of());

        TushareCrossMarketCalculator.Calculation result =
                new TushareCrossMarketCalculator().calculate(
                        fixture.cnOpenDates(),
                        fixture.benchmarkCloses(),
                        incomplete,
                        LAST_CN_SESSION);

        assertThat(result.points()).isEmpty();
        assertThat(result.gaps()).anySatisfy(gap ->
                assertThat(gap).contains("SPX", "2026-03-30"));
    }

    @Test
    void requiresSixtyAlignedReturnPairsForPearsonCorrelation() {
        Fixture fixture = correlatedFixture();
        List<LocalDate> onlySixtySessions =
                fixture.cnOpenDates().subList(2, 62);

        TushareCrossMarketCalculator.Calculation result =
                new TushareCrossMarketCalculator().calculate(
                        onlySixtySessions,
                        fixture.benchmarkCloses(),
                        fixture.globalCloses(),
                        LAST_CN_SESSION);

        assertThat(result.points()).isEmpty();
        assertThat(result.gaps()).anySatisfy(gap ->
                assertThat(gap).contains("60 aligned return pairs"));
    }

    private static Fixture correlatedFixture() {
        List<LocalDate> cnOpenDates = weekdaysEndingAt(
                LAST_CN_SESSION, 62);
        Map<LocalDate, BigDecimal> benchmark = new LinkedHashMap<>();
        BigDecimal close = new BigDecimal("100");
        benchmark.put(cnOpenDates.getFirst(), close);
        for (int index = 1; index < cnOpenDates.size(); index++) {
            BigDecimal dailyReturn = index % 2 == 0
                    ? new BigDecimal("0.0100")
                    : new BigDecimal("-0.0050");
            close = close.multiply(BigDecimal.ONE.add(dailyReturn));
            benchmark.put(cnOpenDates.get(index), close);
        }

        Map<LocalDate, BigDecimal> asia = new LinkedHashMap<>(benchmark);
        Map<LocalDate, BigDecimal> unitedStates = new LinkedHashMap<>();
        cnOpenDates.forEach(date -> unitedStates.put(
                previousWeekday(date), benchmark.get(date)));
        unitedStates.put(LAST_CN_SESSION, new BigDecimal("999999999"));

        Map<String, Map<LocalDate, BigDecimal>> global = new LinkedHashMap<>();
        global.put("SPX", Map.copyOf(unitedStates));
        global.put("IXIC", Map.copyOf(unitedStates));
        global.put("HSI", Map.copyOf(asia));
        global.put("N225", Map.copyOf(asia));
        return new Fixture(
                List.copyOf(cnOpenDates),
                Map.copyOf(benchmark),
                Map.copyOf(global));
    }

    private static List<LocalDate> weekdaysEndingAt(
            LocalDate endDate,
            int size
    ) {
        List<LocalDate> reversed = new ArrayList<>();
        LocalDate date = endDate;
        while (reversed.size() < size) {
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                reversed.add(date);
            }
            date = date.minusDays(1);
        }
        return reversed.reversed();
    }

    private static LocalDate previousWeekday(LocalDate date) {
        LocalDate previous = date.minusDays(1);
        while (previous.getDayOfWeek() == DayOfWeek.SATURDAY
                || previous.getDayOfWeek() == DayOfWeek.SUNDAY) {
            previous = previous.minusDays(1);
        }
        return previous;
    }

    private record Fixture(
            List<LocalDate> cnOpenDates,
            Map<LocalDate, BigDecimal> benchmarkCloses,
            Map<String, Map<LocalDate, BigDecimal>> globalCloses
    ) {
    }
}
