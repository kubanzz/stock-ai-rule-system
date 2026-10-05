package com.jx.tracker.scheduler;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.factor.ResearchReboundFactorCalculator;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.rule.service.RuleStrategyService;
import com.jx.tracker.service.ResearchReboundApplicationScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StrategyStockPoolMarketDataCollectionTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

    @Test
    void defaultWorkflowUsesGenericBoundSnapshotWithoutMyFollowOrResearchWarmup() {
        Fixture fixture = new Fixture(List.of("000547.SZ", "600000.SH"));
        DailyWorkflowContext context = fixture.context(null);

        fixture.handler.execute(context);

        assertThat(context.getRequest().getSymbols()).containsExactly("000547.SZ", "600000.SH");
        ArgumentCaptor<DailyQuoteSyncRequestDto> requests = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(fixture.sync, times(2)).syncDailyQuotes(requests.capture());
        assertThat(requests.getAllValues()).extracting(DailyQuoteSyncRequestDto::getTargetSymbol)
                .containsExactly("000547.SZ", "600000.SH");
        assertThat(requests.getAllValues()).allSatisfy(request ->
                assertThat(request.getStartDate()).isEqualTo(DATE.minusDays(60)));
        verifyNoInteractions(fixture.watchlists, fixture.watchlistItems, fixture.calculator, fixture.quotes);
    }

    @Test
    void explicitSymbolsAreIntersectedWithBoundPoolBeforeAnyQuotesAreSynced() {
        Fixture fixture = new Fixture(List.of("000547.SZ", "600000.SH"));
        DailyWorkflowContext context = fixture.context(List.of("sz000547", "600519.SH"));

        fixture.handler.execute(context);

        assertThat(context.getRequest().getSymbols()).containsExactly("000547.SZ");
        ArgumentCaptor<DailyQuoteSyncRequestDto> request = ArgumentCaptor.forClass(DailyQuoteSyncRequestDto.class);
        verify(fixture.sync).syncDailyQuotes(request.capture());
        assertThat(request.getValue().getTargetSymbol()).isEqualTo("000547.SZ");
    }

    @Test
    void requestOutsideBoundPoolStopsBeforeProviderCallsAndDoesNotFallBackToMarket() {
        Fixture fixture = new Fixture(List.of("000547.SZ"));

        assertThatThrownBy(() -> fixture.handler.execute(fixture.context(List.of("600519.SH"))))
                .isInstanceOf(ServiceException.class).hasMessageContaining("没有可用交集");

        verifyNoInteractions(fixture.sync, fixture.watchlists, fixture.watchlistItems, fixture.quotes);
    }

    @Test
    void missingBoundSnapshotStopsNullAndEmptyRequestsWithoutMarketFallback() {
        Fixture fixture = new Fixture(null);

        assertThatThrownBy(() -> fixture.handler.execute(fixture.context(null)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("没有可用交集");
        fixture.strategy.setStockPoolSymbols(List.of());
        assertThatThrownBy(() -> fixture.handler.execute(fixture.context(List.of())))
                .isInstanceOf(ServiceException.class).hasMessageContaining("没有可用交集");

        verifyNoInteractions(fixture.sync, fixture.watchlists, fixture.watchlistItems, fixture.quotes);
    }

    @Test
    void providerFallbackRetainsOnlyTheBoundIntersectionForDownstreamSteps() {
        Fixture fixture = new Fixture(List.of("000547.SZ"));
        when(fixture.sync.syncStockList(any())).thenThrow(new ServiceException("行情源不可用"));
        when(fixture.quotes.selectList(any())).thenReturn(List.of(
                StockDailyQuote.builder().symbol("000547.SZ").tradeDate(DATE)
                        .closePrice(BigDecimal.TEN).build()));
        DailyWorkflowContext context = fixture.context(List.of("000547.SZ", "600519.SH"));

        var result = fixture.handler.execute(context);

        assertThat(result.getDetails()).containsEntry("localQuoteFallback", true);
        assertThat(context.getRequest().getSymbols()).containsExactly("000547.SZ");
        verify(fixture.quotes).selectList(any());
    }

    private static class Fixture {
        final MarketDataSyncService sync = mock(MarketDataSyncService.class);
        final StockWatchlistMapper watchlists = mock(StockWatchlistMapper.class);
        final StockWatchlistItemMapper watchlistItems = mock(StockWatchlistItemMapper.class);
        final StockDailyQuoteMapper quotes = mock(StockDailyQuoteMapper.class);
        final ResearchReboundFactorCalculator calculator = mock(ResearchReboundFactorCalculator.class);
        final RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        final MarketDataCollectionStepHandler handler;

        Fixture(List<String> symbols) {
            strategy.setStrategyCode("user-defined-plan");
            strategy.setStockPoolType("watchlist");
            strategy.setStockPoolSymbols(symbols);
            RuleStrategyService strategies = mock(RuleStrategyService.class);
            when(strategies.getActiveStrategies()).thenReturn(List.of(strategy));
            var scope = new ResearchReboundApplicationScope(strategies, calculator, quotes);
            MarketDataSyncResultDto success = new MarketDataSyncResultDto();
            success.setStatus("success");
            when(sync.syncStockList(any())).thenReturn(success);
            when(sync.syncTradeCalendar(any())).thenReturn(success);
            when(sync.syncDailyQuotes(any())).thenReturn(success);
            TradeCalendarService calendar = mock(TradeCalendarService.class);
            when(calendar.pageTradeCalendars(any())).thenReturn(PageResult.getDataTable(List.of(
                    TradeCalendar.builder().tradeDate(DATE).open(true).build()), 1L));
            handler = new MarketDataCollectionStepHandler(sync, calendar, watchlists, watchlistItems, quotes, scope);
        }

        DailyWorkflowContext context(List<String> symbols) {
            DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
            request.setTradeDate(DATE);
            request.setSymbols(symbols);
            return new DailyWorkflowContext("stock-pool-test", request, WorkflowTriggerType.SCHEDULED);
        }
    }
}
