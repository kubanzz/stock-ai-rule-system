package com.jx.tracker.backtest;

import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MarketDataProviderSelection;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.controller.BacktestController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HistoricalBacktestQuotePreparationServiceTest {

    private final StockDailyQuoteMapper quotes = mock(StockDailyQuoteMapper.class);
    private final StockWatchlistMapper watchlists = mock(StockWatchlistMapper.class);
    private final StockWatchlistItemMapper items = mock(StockWatchlistItemMapper.class);
    private final MarketDataProviderResolver resolver = mock(MarketDataProviderResolver.class);
    private final MarketDataSyncService sync = mock(MarketDataSyncService.class);
    private final HistoricalBacktestQuotePreparationService service =
            new HistoricalBacktestQuotePreparationService(quotes, watchlists, items, resolver, sync);

    @Test
    void sparseCustomPoolSyncsFullRangeWarmupAndHoldingTailBeforeBacktest() {
        BacktestRequestDto request = request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 20));
        when(quotes.selectList(any())).thenReturn(List.of(quote(LocalDate.of(2026, 6, 10))))
                .thenReturn(weekdayQuotes(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 7, 6)));
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(null, "aktools/akshare", false, null));
        MarketDataSyncResultDto syncResult = new MarketDataSyncResultDto();
        syncResult.setStatus("success");
        syncResult.setDataSource("aktools/akshare");
        syncResult.setInserted(50);
        when(sync.syncDailyQuotes(any())).thenReturn(syncResult);

        service.prepare(request);

        ArgumentCaptor<DailyQuoteSyncRequestDto> captured = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(sync).syncDailyQuotes(captured.capture());
        assertThat(captured.getValue().getTargetSymbol()).isEqualTo("000001.SZ");
        assertThat(captured.getValue().getStartDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(captured.getValue().getEndDate()).isAfter(request.getEndDate());
        assertThat(request.isForceFactorRecalculation()).isTrue();
    }

    @Test
    void completeLocalHistoryNeedsNoProvider() {
        BacktestRequestDto request = request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1));
        when(quotes.selectList(any())).thenReturn(weekdayQuotes(
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 17)));

        service.prepare(request);

        verify(resolver, never()).resolve();
        verify(sync, never()).syncDailyQuotes(any());
        assertThat(request.isForceFactorRecalculation()).isFalse();
    }

    @Test
    void missingQuotesNeverUseMockFallback() {
        BacktestRequestDto request = request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 20));
        when(quotes.selectList(any())).thenReturn(List.of());
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(null, "mock", true, "missing token"));

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("真实行情源");
        verify(sync, never()).syncDailyQuotes(any());
    }

    @Test
    void sparseWatchlistUsesOnlyItsSelectedSymbols() {
        BacktestRequestDto request = request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 20));
        request.setStockPoolType("watchlist");
        request.setSymbols(null);
        request.setPoolCode("my-follow");
        when(watchlists.selectOne(any())).thenReturn(StockWatchlist.builder()
                .id(3L).poolCode("my-follow").build());
        when(items.selectList(any())).thenReturn(List.of(StockWatchlistItem.builder()
                .watchlistId(3L).symbol("sz000001").build()));
        when(quotes.selectList(any())).thenReturn(List.of(quote(LocalDate.of(2026, 6, 10))))
                .thenReturn(weekdayQuotes(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 7, 6)));
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(null, "aktools/akshare", false, null));
        MarketDataSyncResultDto syncResult = new MarketDataSyncResultDto();
        syncResult.setStatus("success");
        syncResult.setDataSource("aktools/akshare");
        syncResult.setInserted(30);
        when(sync.syncDailyQuotes(any())).thenReturn(syncResult);

        service.prepare(request);

        ArgumentCaptor<DailyQuoteSyncRequestDto> captured = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(sync).syncDailyQuotes(captured.capture());
        assertThat(captured.getValue().getTargetSymbol()).isEqualTo("000001.SZ");
    }

    @Test
    void syncSuccessWithTooFewQuotesDoesNotClaimFactorCoverage() {
        BacktestRequestDto request = request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 20));
        when(quotes.selectList(any())).thenReturn(List.of())
                .thenReturn(weekdayQuotes(LocalDate.of(2026, 6, 10), request.getEndDate()));
        when(resolver.resolve()).thenReturn(new MarketDataProviderSelection(null, "aktools/akshare", false, null));
        MarketDataSyncResultDto syncResult = new MarketDataSyncResultDto();
        syncResult.setStatus("success");
        syncResult.setDataSource("aktools/akshare");
        syncResult.setInserted(7);
        when(sync.syncDailyQuotes(any())).thenReturn(syncResult);

        assertThatThrownBy(() -> service.prepare(request))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("26 条");
    }

    @Test
    void controllerPreparesQuotesBeforeTransactionalReplay() {
        SingleRuleBacktestService backtest = mock(SingleRuleBacktestService.class);
        HistoricalBacktestQuotePreparationService preparation = mock(HistoricalBacktestQuotePreparationService.class);
        BacktestRequestDto request = request(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 20));
        BacktestResult result = new BacktestResult();
        when(backtest.runSingleRuleBacktest(request)).thenReturn(result);

        new BacktestController(backtest, preparation).runBacktest(request);

        InOrder order = inOrder(preparation, backtest);
        order.verify(preparation).prepare(request);
        order.verify(backtest).runSingleRuleBacktest(request);
    }

    private BacktestRequestDto request(LocalDate start, LocalDate end) {
        BacktestRequestDto request = new BacktestRequestDto();
        request.setStartDate(start);
        request.setEndDate(end);
        request.setObjectType("rule");
        request.setObjectCode("TEST_RULE");
        request.setHoldingPeriod(1);
        request.setStockPoolType("custom");
        request.setSymbols(List.of("sz000001"));
        return request;
    }

    private List<StockDailyQuote> weekdayQuotes(LocalDate start, LocalDate end) {
        return start.datesUntil(end.plusDays(1))
                .filter(date -> date.getDayOfWeek() != DayOfWeek.SATURDAY
                        && date.getDayOfWeek() != DayOfWeek.SUNDAY)
                .map(this::quote)
                .toList();
    }

    private StockDailyQuote quote(LocalDate date) {
        return StockDailyQuote.builder()
                .symbol("000001.SZ")
                .tradeDate(date)
                .closePrice(BigDecimal.TEN)
                .volume(BigDecimal.valueOf(100))
                .dataSource("aktools/akshare")
                .build();
    }
}
