package com.jx.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.SignalBackfillRun;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.mapper.SignalBackfillRunMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.provider.MarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MarketDataProviderSelection;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.signal.service.StockSignalService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ResearchReboundSignalBackfillTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);
    private static final String SYMBOL = "000547.SZ";

    @Test
    void scheduledBackfillIncludesActiveUniverseWithoutWatchlistAndFetchesBenchmark() {
        Fixture fixture = new Fixture(true);
        var run = fixture.service.get(fixture.service.start().runId());
        assertThat(run.status()).as("%s", run.failures()).isEqualTo("SUCCESS");
        assertThat(run.totalSymbols()).isEqualTo(1);
        assertThat(run.generatedSignals()).isEqualTo(1);
        ArgumentCaptor<DailyQuoteSyncRequestDto> requests = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(fixture.sync, times(2)).syncDailyQuotes(requests.capture());
        assertThat(requests.getAllValues()).extracting(DailyQuoteSyncRequestDto::getTargetSymbol)
                .containsExactly("000300.SH", SYMBOL);
        assertThat(requests.getAllValues().getLast().getStartDate()).isEqualTo(DATE);
    }

    @Test
    void missingSameDayBenchmarkStopsNewSignalEvenWhenStockHistoryIsComplete() {
        Fixture fixture = new Fixture(false);
        var run = fixture.service.get(fixture.service.start().runId());
        assertThat(run.status()).isEqualTo("PARTIAL");
        assertThat(run.generatedSignals()).isZero();
        assertThat(run.failures()).anySatisfy(failure -> assertThat(failure.reason()).contains("沪深300"));
        verify(fixture.factors).calculateAndSaveFromRealQuotes(any());
        verify(fixture.signals).backfillMissingSignalsBatch(any(), any(), any(), anyBoolean());
    }

    @Test
    void genericBoundPoolOnlyProcessesItsSnapshotAndDoesNotRequireResearchHistoryOrIndex() {
        Fixture fixture = new Fixture(true);
        when(fixture.scope.activeSymbols()).thenReturn(Set.of());
        var run = fixture.service.get(fixture.service.start().runId());
        assertThat(run.status()).as("%s", run.failures()).isEqualTo("SUCCESS");
        assertThat(run.totalSymbols()).isEqualTo(1);
        assertThat(run.generatedSignals()).isEqualTo(1);
        verifyNoInteractions(fixture.watchlists, fixture.watchlistItems);
        ArgumentCaptor<DailyQuoteSyncRequestDto> requests = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(fixture.sync).syncDailyQuotes(requests.capture());
        assertThat(requests.getValue().getTargetSymbol()).isEqualTo(SYMBOL);
        verify(fixture.scope, never()).historyStart(any(), any());
    }

    @Test
    void emptyBoundPoolStopsBeforeCalendarSyncAndNeverFallsBackToWatchlist() {
        Fixture fixture = new Fixture(true);
        when(fixture.scope.activeApplicationSymbols()).thenReturn(Set.of());
        var run = fixture.service.get(fixture.service.start().runId());
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.totalSymbols()).isZero();
        assertThat(run.failures()).anySatisfy(failure -> assertThat(failure.reason()).contains("股票分组快照"));
        verifyNoInteractions(fixture.watchlists, fixture.watchlistItems, fixture.sync, fixture.factors, fixture.signals);
    }

    @Test
    void resynchronizesLegacyTushareRowsInsideTheExistingWindowWithoutTouchingOtherSources() {
        Fixture fixture = new Fixture(true);
        when(fixture.resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(MarketDataProvider.class), "tushare", false, null));
        when(fixture.scope.activeSymbols()).thenReturn(Set.of());
        LocalDate first = DATE.minusDays(1);
        when(fixture.calendar.pageTradeCalendars(any())).thenReturn(PageResult.getDataTable(List.of(
                TradeCalendar.builder().tradeDate(first).open(true).dataSource("tushare").build(),
                TradeCalendar.builder().tradeDate(DATE).open(true).dataSource("tushare").build()), 2L));
        includeEarlierKnownQuote(fixture, first);
        StockDailyQuote legacy = quote(first);
        legacy.setDataSource("tushare");
        when(fixture.quotes.selectList(any())).thenReturn(List.of(legacy, quote(DATE)));

        fixture.service.get(fixture.service.start().runId());

        ArgumentCaptor<DailyQuoteSyncRequestDto> requests = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(fixture.sync).syncDailyQuotes(requests.capture());
        assertThat(requests.getValue().getStartDate()).isEqualTo(first);
        assertThat(requests.getValue().getEndDate()).isEqualTo(DATE);
    }

    @Test
    void leavesCompleteOtherProviderHistoryOutsideTheLatestRefreshUntouched() {
        Fixture fixture = new Fixture(true);
        when(fixture.resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(MarketDataProvider.class), "tushare", false, null));
        when(fixture.scope.activeSymbols()).thenReturn(Set.of());
        LocalDate first = DATE.minusDays(1);
        when(fixture.calendar.pageTradeCalendars(any())).thenReturn(PageResult.getDataTable(List.of(
                TradeCalendar.builder().tradeDate(first).open(true).dataSource("tushare").build(),
                TradeCalendar.builder().tradeDate(DATE).open(true).dataSource("tushare").build()), 2L));
        includeEarlierKnownQuote(fixture, first);
        when(fixture.quotes.selectList(any())).thenReturn(List.of(quote(first), quote(DATE)));

        fixture.service.get(fixture.service.start().runId());

        ArgumentCaptor<DailyQuoteSyncRequestDto> requests = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(fixture.sync).syncDailyQuotes(requests.capture());
        assertThat(requests.getValue().getStartDate()).isEqualTo(DATE);
        assertThat(requests.getValue().getEndDate()).isEqualTo(DATE);
    }

    @Test
    void failedLegacyTushareRefreshCannotPublishSignalsFromTheOldUnitValues() {
        Fixture fixture = new Fixture(true);
        when(fixture.resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(MarketDataProvider.class), "tushare", false, null));
        when(fixture.scope.activeSymbols()).thenReturn(Set.of());
        StockDailyQuote legacy = quote(DATE);
        legacy.setDataSource("tushare");
        when(fixture.quotes.selectOne(any())).thenReturn(legacy);
        when(fixture.quotes.selectList(any())).thenReturn(List.of(legacy));
        MarketDataSyncResultDto failed = new MarketDataSyncResultDto();
        failed.setStatus("failed");
        when(fixture.sync.syncDailyQuotes(any())).thenReturn(failed);

        var run = fixture.service.get(fixture.service.start().runId());

        assertThat(run.status()).isEqualTo("PARTIAL");
        assertThat(run.generatedSignals()).isZero();
        assertThat(run.syncedQuotes()).isZero();
        assertThat(run.missingQuotes()).anySatisfy(gap -> assertThat(gap.reason()).contains("旧 Tushare"));
        verifyNoInteractions(fixture.factors, fixture.signals);
    }

    @Test
    void isolatedLegacyTushareInsideRecentFactorWindowBlocksCalculationDespite26OlderValidRows() {
        Fixture fixture = new Fixture(true);
        when(fixture.resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(MarketDataProvider.class), "tushare", false, null));
        List<StockDailyQuote> history = IntStream.range(0, 30)
                .mapToObj(offset -> quote(DATE.minusDays(offset))).toList();
        history.get(4).setDataSource("tushare");
        when(fixture.quotes.selectList(any())).thenReturn(history);

        var run = fixture.service.get(fixture.service.start().runId());

        assertThat(run.status()).isEqualTo("PARTIAL");
        assertThat(run.generatedSignals()).isZero();
        assertThat(run.failures()).anySatisfy(failure -> assertThat(failure.reason()).contains("真实行情历史不足"));
        verifyNoInteractions(fixture.factors);
        verify(fixture.signals, never()).backfillMissingSignalsBatch(any(), any(), any(), anyBoolean());
    }

    private void includeEarlierKnownQuote(Fixture fixture, LocalDate first) {
        AtomicInteger lookups = new AtomicInteger();
        when(fixture.quotes.selectOne(any())).thenAnswer(invocation -> {
            int index = lookups.getAndIncrement();
            StockDailyQuote quote = quote(index == 1 ? first : DATE);
            quote.setSyncTime(LocalDateTime.of(2026, 9, 30, index >= 3 ? 18 : 14, 0));
            return quote;
        });
    }

    private static class Fixture {
        final MarketDataSyncService sync = mock(MarketDataSyncService.class);
        final IStockFactorDailyService factors = mock(IStockFactorDailyService.class);
        final StockSignalService signals = mock(StockSignalService.class);
        final ResearchReboundApplicationScope scope = mock(ResearchReboundApplicationScope.class);
        final StockWatchlistMapper watchlists = mock(StockWatchlistMapper.class);
        final StockWatchlistItemMapper watchlistItems = mock(StockWatchlistItemMapper.class);
        final MarketDataProviderResolver resolver = mock(MarketDataProviderResolver.class);
        final TradeCalendarService calendar = mock(TradeCalendarService.class);
        final StockDailyQuoteMapper quotes = mock(StockDailyQuoteMapper.class);
        final SignalBackfillService service;

        Fixture(boolean hasBenchmark) {
            when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                    mock(MarketDataProvider.class), "aktools/akshare", false, null));
            MarketDataSyncResultDto success = new MarketDataSyncResultDto();
            success.setStatus("success");
            success.setScanned(1);
            when(sync.syncTradeCalendar(any())).thenReturn(success);
            when(sync.syncDailyQuotes(any())).thenReturn(success);
            when(calendar.pageTradeCalendars(any())).thenReturn(PageResult.getDataTable(List.of(
                    TradeCalendar.builder().tradeDate(DATE).open(true).dataSource("aktools/akshare").build()), 1L));
            when(scope.hasBoundStockPool()).thenReturn(true);
            when(scope.activeApplicationSymbols()).thenReturn(Set.of(SYMBOL));
            when(scope.activeSymbols()).thenReturn(Set.of(SYMBOL));
            when(scope.historyStart(SYMBOL, DATE)).thenReturn(DATE.minusDays(60));
            when(quotes.selectCount(any())).thenReturn(252L);
            AtomicInteger lookups = new AtomicInteger();
            when(quotes.selectOne(any())).thenAnswer(invocation -> {
                StockDailyQuote quote = quote(DATE);
                quote.setSyncTime(LocalDateTime.of(2026, 9, 30, lookups.getAndIncrement() == 3 ? 18 : 14, 0));
                return quote;
            });
            AtomicInteger lists = new AtomicInteger();
            when(quotes.selectList(any())).thenAnswer(invocation -> {
                int index = lists.getAndIncrement();
                if (index == 0) {
                    return List.of(quote(DATE));
                }
                if (index == 1) {
                    return hasBenchmark ? IntStream.range(0, 60)
                            .mapToObj(offset -> quote(DATE.minusDays(offset))).toList() : List.of();
                }
                return IntStream.range(0, 252).mapToObj(offset -> quote(DATE.minusDays(offset))).toList();
            });
            when(factors.calculateAndSaveFromRealQuotes(any())).thenReturn(StockFactorDailyVo.builder()
                    .symbol(SYMBOL).tradeDate(DATE).factors(Map.of("data_status", "normal")).build());
            when(signals.needsSignalGeneration(any(), any(), anyBoolean())).thenReturn(true);
            when(signals.backfillMissingSignalsBatch(any(), any(), any(), anyBoolean()))
                    .thenReturn(hasBenchmark
                            ? new StockSignalService.SignalGenerationBatch(List.of(StockSignalDaily.builder().symbol(SYMBOL).signalDate(DATE).build()), List.of())
                            : new StockSignalService.SignalGenerationBatch(List.of(), List.of(new StockSignalService.SignalGenerationFailure("RS_TEST", "v1", "沪深300同日行情缺失"))));
            var runs = mock(SignalBackfillRunMapper.class);
            Map<String, SignalBackfillRun> store = new HashMap<>();
            when(runs.insert(any(SignalBackfillRun.class))).thenAnswer(invocation -> {
                SignalBackfillRun run = invocation.getArgument(0); store.put(run.getRunId(), run); return 1;
            });
            when(runs.updateById(any(SignalBackfillRun.class))).thenAnswer(invocation -> {
                SignalBackfillRun run = invocation.getArgument(0); store.put(run.getRunId(), run); return 1;
            });
            when(runs.selectById(any())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
            service = new SignalBackfillService(resolver, sync, calendar,
                    watchlists, watchlistItems, quotes,
                    mock(StockSignalDailyMapper.class), factors, signals, runs,
                    new ObjectMapper().findAndRegisterModules(), Runnable::run,
                    Clock.fixed(Instant.parse("2026-09-30T10:30:00Z"), ZoneId.of("Asia/Shanghai")), scope);
        }
    }

    private static StockDailyQuote quote(LocalDate date) {
        return StockDailyQuote.builder().symbol(SYMBOL).tradeDate(date).openPrice(BigDecimal.TEN)
                .highPrice(BigDecimal.TEN).lowPrice(BigDecimal.TEN).closePrice(BigDecimal.TEN)
                .volume(BigDecimal.ONE).amount(BigDecimal.TEN).dataSource("aktools/akshare").build();
    }
}
