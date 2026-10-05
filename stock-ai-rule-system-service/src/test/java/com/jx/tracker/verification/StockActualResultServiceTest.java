package com.jx.tracker.verification;

import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class StockActualResultServiceTest {

    private final FakeMapper<StockSignalDailyMapper, StockSignalDaily> signalMapper = fakeMapper(StockSignalDailyMapper.class);
    private final FakeMapper<StockDailyQuoteMapper, StockDailyQuote> quoteMapper = fakeMapper(StockDailyQuoteMapper.class);
    private final FakeMapper<StockActualResultMapper, StockActualResult> actualResultMapper = fakeMapper(StockActualResultMapper.class);
    private final StockActualResultService service = new StockActualResultService(
            signalMapper.mapper,
            quoteMapper.mapper,
            actualResultMapper.mapper,
            new PredictionHitPolicy()
    );

    @Test
    void writesActualResultAndPredictionHitsFromFutureQuoteSlices() {
        StockSignalDaily signal = StockSignalDaily.builder()
                .symbol("AAPL")
                .signalDate(LocalDate.of(2026, 1, 2))
                .signal(SignalType.BULLISH.getCode())
                .build();
        signalMapper.selectResponses.add(List.of(signal));
        quoteMapper.selectResponses.add(List.of(
                quote("AAPL", LocalDate.of(2026, 1, 2), "100.00"),
                quote("AAPL", LocalDate.of(2026, 1, 3), "102.00"),
                quote("AAPL", LocalDate.of(2026, 1, 4), "102.50"),
                quote("AAPL", LocalDate.of(2026, 1, 5), "103.00"),
                quote("AAPL", LocalDate.of(2026, 1, 6), "104.00"),
                quote("AAPL", LocalDate.of(2026, 1, 7), "105.00"),
                quote("AAPL", LocalDate.of(2026, 1, 8), "106.00"),
                quote("AAPL", LocalDate.of(2026, 1, 9), "107.00"),
                quote("AAPL", LocalDate.of(2026, 1, 10), "108.00"),
                quote("AAPL", LocalDate.of(2026, 1, 11), "109.00"),
                quote("AAPL", LocalDate.of(2026, 1, 12), "110.00")
        ));

        List<StockActualResult> results = service.verifySignals(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2));

        assertThat(results).hasSize(1);
        StockActualResult result = results.getFirst();
        assertThat(result.getReturn1d()).isEqualByComparingTo("0.0200");
        assertThat(result.getReturn3d()).isEqualByComparingTo("0.0300");
        assertThat(result.getReturn5d()).isEqualByComparingTo("0.0500");
        assertThat(result.getReturn10d()).isEqualByComparingTo("0.1000");
        assertThat(result.getHit1d()).isTrue();
        assertThat(result.getHit5d()).isTrue();
        assertThat(actualResultMapper.inserted).containsExactly(result);
    }

    @Test
    void excludesBackfilledSignalsFromHistoricalPredictionVerification() {
        StockSignalDaily backfilled = StockSignalDaily.builder()
                .symbol("AAPL")
                .signalDate(LocalDate.of(2026, 1, 2))
                .signal(SignalType.BULLISH.getCode())
                .generationType("backfill")
                .build();
        signalMapper.selectResponses.add(List.of(backfilled));

        List<StockActualResult> results = service.verifySignals(
                LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2));

        assertThat(results).isEmpty();
        assertThat(actualResultMapper.inserted).isEmpty();
    }

    @Test
    void verifiesOppositeSignalsForSameStockAndDateSeparately() {
        LocalDate date = LocalDate.of(2026, 1, 2);
        StockSignalDaily bullish = StockSignalDaily.builder().id(71L).symbol("AAPL").signalDate(date)
                .strategyCode("PLAN_A").strategyVersion("v1").signal("bullish").build();
        StockSignalDaily bearish = StockSignalDaily.builder().id(72L).symbol("AAPL").signalDate(date)
                .strategyCode("PLAN_B").strategyVersion("v3").signal("bearish").build();
        signalMapper.selectResponses.add(List.of(bullish, bearish));
        List<StockDailyQuote> prices = List.of(quote("AAPL", date, "100"), quote("AAPL", date.plusDays(1), "102"));
        quoteMapper.selectResponses.add(prices);
        quoteMapper.selectResponses.add(prices);

        List<StockActualResult> results = service.verifySignals(date, date);

        assertThat(results).extracting(StockActualResult::getSignalId).containsExactly(71L, 72L);
        assertThat(results).extracting(StockActualResult::getStrategyCode).containsExactly("PLAN_A", "PLAN_B");
        assertThat(results).extracting(StockActualResult::getStrategyVersion).containsExactly("v1", "v3");
        assertThat(results).extracting(StockActualResult::getHit1d).containsExactly(true, false);
        assertThat(actualResultMapper.inserted).hasSize(2);
    }

    private StockDailyQuote quote(String symbol, LocalDate tradeDate, String closePrice) {
        return StockDailyQuote.builder()
                .symbol(symbol)
                .tradeDate(tradeDate)
                .closePrice(new BigDecimal(closePrice))
                .build();
    }

    @SuppressWarnings("unchecked")
    private <M, E> FakeMapper<M, E> fakeMapper(Class<M> mapperClass) {
        FakeMapper<M, E> fake = new FakeMapper<>();
        fake.mapper = (M) Proxy.newProxyInstance(
                mapperClass.getClassLoader(),
                new Class<?>[]{mapperClass},
                (proxy, method, args) -> {
                    if ("selectList".equals(method.getName())) {
                        return fake.selectResponses.isEmpty() ? List.of() : fake.selectResponses.remove();
                    }
                    if ("insert".equals(method.getName()) || "upsertActualResult".equals(method.getName())) {
                        fake.inserted.add((E) args[0]);
                        return 1;
                    }
                    return null;
                }
        );
        return fake;
    }

    private static class FakeMapper<M, E> {
        private M mapper;
        private final Queue<List<E>> selectResponses = new ArrayDeque<>();
        private final List<E> inserted = new ArrayList<>();
    }
}
