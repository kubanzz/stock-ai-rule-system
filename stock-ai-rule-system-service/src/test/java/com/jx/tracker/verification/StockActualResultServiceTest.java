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
                    if ("insert".equals(method.getName())) {
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
