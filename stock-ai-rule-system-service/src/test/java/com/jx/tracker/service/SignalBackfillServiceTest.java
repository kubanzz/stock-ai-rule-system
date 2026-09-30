package com.jx.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.entity.SignalBackfillRun;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.mapper.SignalBackfillRunMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignalBackfillServiceTest {

    @Test
    void fillsEveryOpenDayAfterLastQuoteAndCalculatesInDateOrder() {
        LocalDate first = LocalDate.of(2026, 9, 22);
        List<LocalDate> days = List.of(first, first.plusDays(1), first.plusDays(2), first.plusDays(3));
        MarketDataProviderResolver providerResolver = mock(MarketDataProviderResolver.class);
        when(providerResolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(com.jx.tracker.market.data.provider.MarketDataProvider.class),
                "aktools/akshare", false, null));
        MarketDataSyncService syncService = mock(MarketDataSyncService.class);
        MarketDataSyncResultDto success = new MarketDataSyncResultDto();
        success.setStatus("success");
        success.setScanned(3);
        when(syncService.syncTradeCalendar(any())).thenReturn(success);
        when(syncService.syncDailyQuotes(any())).thenReturn(success);
        TradeCalendarService calendarService = mock(TradeCalendarService.class);
        when(calendarService.pageTradeCalendars(any())).thenReturn(PageResult.getDataTable(days.stream()
                .map(day -> TradeCalendar.builder().market("CN").tradeDate(day).open(true)
                        .dataSource("aktools/akshare").build()).toList(), 4L));
        when(calendarService.getByMarketAndTradeDate(any(), any())).thenAnswer(invocation ->
                TradeCalendar.builder().market("CN").tradeDate(invocation.getArgument(1))
                        .open(false).dataSource("aktools/akshare").build());

        StockWatchlistMapper watchlistMapper = mock(StockWatchlistMapper.class);
        when(watchlistMapper.selectOne(any())).thenReturn(StockWatchlist.builder().id(1L)
                .poolCode("my-follow").build());
        StockWatchlistItemMapper itemMapper = mock(StockWatchlistItemMapper.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(StockWatchlistItem.builder()
                .watchlistId(1L).symbol("600519.SH").build()));
        StockDailyQuoteMapper quoteMapper = mock(StockDailyQuoteMapper.class);
        when(quoteMapper.selectCount(any())).thenReturn(26L);
        AtomicInteger quoteLookups = new AtomicInteger();
        when(quoteMapper.selectOne(any())).thenAnswer(invocation -> {
            int index = quoteLookups.getAndIncrement();
            if (index < 2) {
                return quote(first);
            }
            if (index == 2) {
                StockDailyQuote result = quote(days.getLast());
                result.setSyncTime(java.time.LocalDateTime.of(2026, 9, 25, 14, 0));
                return result;
            }
            if (index == 3) {
                StockDailyQuote result = quote(days.getLast());
                result.setSyncTime(java.time.LocalDateTime.of(2026, 9, 25, 15, 0));
                return result;
            }
            return quote(days.get(index - 4));
        });
        AtomicInteger quoteLists = new AtomicInteger();
        when(quoteMapper.selectList(any())).thenAnswer(invocation ->
                quoteLists.getAndIncrement() == 0 ? List.of(quote(first))
                        : java.util.stream.IntStream.range(0, 26)
                                .mapToObj(offset -> quote(first.minusDays(offset))).toList());
        StockSignalDailyMapper signalMapper = mock(StockSignalDailyMapper.class);
        IStockFactorDailyService factorService = mock(IStockFactorDailyService.class);
        when(factorService.calculateAndSaveFromRealQuotes(any())).thenAnswer(invocation -> {
            TechnicalFactorCalculateRequestDto request = invocation.getArgument(0);
            return StockFactorDailyVo.builder().symbol(request.getSymbol())
                    .tradeDate(request.getTradeDate()).factors(Map.of("data_status", "normal")).build();
        });
        StockSignalService signalService = mock(StockSignalService.class);
        when(signalService.backfillMissingSignalFromFactors(any(), any(), any()))
                .thenAnswer(invocation -> StockSignalDaily.builder()
                        .symbol(invocation.getArgument(0)).signalDate(invocation.getArgument(1)).build());
        SignalBackfillRunMapper runMapper = mock(SignalBackfillRunMapper.class);
        Map<String, SignalBackfillRun> runs = new HashMap<>();
        when(runMapper.insert(any(SignalBackfillRun.class))).thenAnswer(invocation -> {
            SignalBackfillRun run = invocation.getArgument(0);
            runs.put(run.getRunId(), run);
            return 1;
        });
        when(runMapper.updateById(any(SignalBackfillRun.class))).thenAnswer(invocation -> {
            SignalBackfillRun run = invocation.getArgument(0);
            runs.put(run.getRunId(), run);
            return 1;
        });
        when(runMapper.selectById(any())).thenAnswer(invocation -> runs.get(invocation.getArgument(0)));
        Clock clock = Clock.fixed(Instant.parse("2026-09-25T10:30:00Z"), ZoneId.of("Asia/Shanghai"));
        SignalBackfillService service = new SignalBackfillService(providerResolver, syncService,
                calendarService, watchlistMapper, itemMapper, quoteMapper, signalMapper, factorService,
                signalService, runMapper, new ObjectMapper().findAndRegisterModules(), Runnable::run, clock);

        var run = service.get(service.start().runId());

        assertThat(run.status()).as("failures=%s, gaps=%s", run.failures(), run.missingQuotes())
                .isEqualTo("SUCCESS");
        assertThat(run.totalTasks()).isEqualTo(4);
        assertThat(run.completedTasks()).isEqualTo(4);
        assertThat(run.syncedQuotes()).isEqualTo(3);
        assertThat(run.generatedSignals()).isEqualTo(4);
        ArgumentCaptor<DailyQuoteSyncRequestDto> syncRequest = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(syncService).syncDailyQuotes(syncRequest.capture());
        assertThat(syncRequest.getValue().getStartDate()).isEqualTo(first.plusDays(1));
        assertThat(syncRequest.getValue().getEndDate()).isEqualTo(first.plusDays(3));
        ArgumentCaptor<TechnicalFactorCalculateRequestDto> factors = ArgumentCaptor.forClass(TechnicalFactorCalculateRequestDto.class);
        verify(factorService, org.mockito.Mockito.times(4)).calculateAndSaveFromRealQuotes(factors.capture());
        assertThat(factors.getAllValues()).extracting(TechnicalFactorCalculateRequestDto::getTradeDate)
                .containsExactlyElementsOf(days);
    }

    @Test
    void refusesMockProviderBeforeSchedulingOrWriting() {
        MarketDataProviderResolver resolver = mock(MarketDataProviderResolver.class);
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(com.jx.tracker.market.data.provider.MarketDataProvider.class), "mock", true, "fallback"));
        SignalBackfillRunMapper mapper = mock(SignalBackfillRunMapper.class);
        SignalBackfillService service = new SignalBackfillService(resolver,
                mock(MarketDataSyncService.class), mock(TradeCalendarService.class),
                mock(StockWatchlistMapper.class), mock(StockWatchlistItemMapper.class),
                mock(StockDailyQuoteMapper.class), mock(StockSignalDailyMapper.class),
                mock(IStockFactorDailyService.class), mock(StockSignalService.class),
                mapper, new ObjectMapper().findAndRegisterModules(), Runnable::run,
                Clock.fixed(Instant.parse("2026-09-25T10:30:00Z"), ZoneId.of("Asia/Shanghai")));

        org.assertj.core.api.Assertions.assertThatThrownBy(service::start)
                .hasMessageContaining("模拟数据");
        verify(mapper, never()).insert(any(SignalBackfillRun.class));
    }

    @Test
    void firstRealQuoteAfterListingDoesNotBlockLaterDatesDuringWarmup() {
        LocalDate first = LocalDate.of(2026, 9, 1);
        LocalDate listing = LocalDate.of(2026, 9, 10);
        LocalDate last = LocalDate.of(2026, 9, 25);
        MarketDataProviderResolver resolver = mock(MarketDataProviderResolver.class);
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(
                mock(com.jx.tracker.market.data.provider.MarketDataProvider.class),
                "aktools/akshare", false, null));
        MarketDataSyncService sync = mock(MarketDataSyncService.class);
        MarketDataSyncResultDto success = new MarketDataSyncResultDto();
        success.setStatus("success");
        success.setScanned(1);
        when(sync.syncTradeCalendar(any())).thenReturn(success);
        when(sync.syncDailyQuotes(any())).thenReturn(success);
        TradeCalendarService calendar = mock(TradeCalendarService.class);
        List<LocalDate> dates = List.of(first, listing, last);
        when(calendar.pageTradeCalendars(any())).thenReturn(PageResult.getDataTable(dates.stream()
                .map(day -> TradeCalendar.builder().market("CN").tradeDate(day).open(true)
                        .dataSource("aktools/akshare").build()).toList(), 3L));
        when(calendar.getByMarketAndTradeDate(any(), any())).thenAnswer(invocation ->
                TradeCalendar.builder().tradeDate(invocation.getArgument(1)).open(false)
                        .dataSource("aktools/akshare").build());
        StockWatchlistMapper watchlists = mock(StockWatchlistMapper.class);
        when(watchlists.selectOne(any())).thenReturn(StockWatchlist.builder().id(1L).build());
        StockWatchlistItemMapper items = mock(StockWatchlistItemMapper.class);
        when(items.selectList(any())).thenReturn(List.of(StockWatchlistItem.builder()
                .symbol("600519.SH").build()));
        StockDailyQuoteMapper quotes = mock(StockDailyQuoteMapper.class);
        when(quotes.selectCount(any())).thenReturn(0L);
        AtomicInteger lookups = new AtomicInteger();
        when(quotes.selectOne(any())).thenAnswer(invocation -> {
            int index = lookups.getAndIncrement();
            if (index < 3) {
                return null;
            }
            if (index == 3) {
                return quote(last);
            }
            if (index == 4) {
                StockDailyQuote refreshed = quote(last);
                refreshed.setSyncTime(java.time.LocalDateTime.of(2026, 9, 25, 18, 0));
                return refreshed;
            }
            if (index == 5) {
                return quote(listing);
            }
            return index == 6 ? null : quote(index == 7 ? listing : last);
        });
        AtomicInteger quoteLists = new AtomicInteger();
        when(quotes.selectList(any())).thenAnswer(invocation -> {
            int index = quoteLists.incrementAndGet();
            return index == 1 ? List.of() : java.util.stream.IntStream.range(0, 26)
                    .mapToObj(offset -> quote(last.minusDays(offset))).toList();
        });
        IStockFactorDailyService factors = mock(IStockFactorDailyService.class);
        when(factors.calculateAndSaveFromRealQuotes(any())).thenAnswer(invocation -> {
            TechnicalFactorCalculateRequestDto request = invocation.getArgument(0);
            return StockFactorDailyVo.builder().symbol(request.getSymbol())
                    .tradeDate(request.getTradeDate()).factors(Map.of("data_status", "normal")).build();
        });
        StockSignalService signals = mock(StockSignalService.class);
        when(signals.backfillMissingSignalFromFactors(any(), any(), any()))
                .thenReturn(StockSignalDaily.builder().signalDate(last).build());
        SignalBackfillRunMapper runs = mock(SignalBackfillRunMapper.class);
        Map<String, SignalBackfillRun> store = new HashMap<>();
        when(runs.insert(any(SignalBackfillRun.class))).thenAnswer(invocation -> {
            SignalBackfillRun row = invocation.getArgument(0);
            store.put(row.getRunId(), row);
            return 1;
        });
        when(runs.updateById(any(SignalBackfillRun.class))).thenAnswer(invocation -> {
            SignalBackfillRun row = invocation.getArgument(0);
            store.put(row.getRunId(), row);
            return 1;
        });
        when(runs.selectById(any())).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        SignalBackfillService service = new SignalBackfillService(resolver, sync, calendar,
                watchlists, items, quotes, mock(StockSignalDailyMapper.class), factors, signals,
                runs, new ObjectMapper().findAndRegisterModules(), Runnable::run,
                Clock.fixed(Instant.parse("2026-09-25T10:30:00Z"), ZoneId.of("Asia/Shanghai")));

        var result = service.get(service.start().runId());

        assertThat(result.missingQuotes()).noneMatch(gap -> first.equals(gap.tradeDate()));
        assertThat(result.status()).isNotEqualTo("FAILED");
    }

    private StockDailyQuote quote(LocalDate date) {
        return StockDailyQuote.builder().symbol("600519.SH").tradeDate(date)
                .openPrice(BigDecimal.TEN).highPrice(BigDecimal.TEN)
                .lowPrice(BigDecimal.TEN).closePrice(BigDecimal.TEN)
                .volume(BigDecimal.ONE).dataSource("aktools/akshare").build();
    }
}
