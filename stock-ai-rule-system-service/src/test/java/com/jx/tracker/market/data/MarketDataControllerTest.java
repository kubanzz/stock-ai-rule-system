package com.jx.tracker.market.data;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.market.data.controller.MarketDataController;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRunQueryDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.provider.MockMarketDataProvider;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.StockBaseService;
import com.jx.tracker.market.data.service.StockDailyQuoteService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketDataControllerTest {

    @Test
    void triggerDailyQuoteSyncReturnsAjaxResultContract() {
        MarketDataSyncService syncService = mock(MarketDataSyncService.class);
        MarketDataSyncResultDto syncResult = new MarketDataSyncResultDto();
        syncResult.setRunId(100L);
        syncResult.setStatus("success");
        when(syncService.syncDailyQuotes(any(DailyQuoteSyncRequestDto.class))).thenReturn(syncResult);

        MarketDataController controller = controller(syncService, mock(TradeCalendarService.class));

        AjaxResult response = controller.syncDailyQuotes(new DailyQuoteSyncRequestDto());

        assertThat(response.get(AjaxResult.CODE_TAG)).isEqualTo(200);
        assertThat(response.get(AjaxResult.DATA_TAG)).isSameAs(syncResult);
    }

    @Test
    void querySyncRunsKeepsPageResultContract() {
        MarketDataSyncService syncService = mock(MarketDataSyncService.class);
        PageResult<MarketDataSyncRun> page = PageResult.getDataTable(List.of(MarketDataSyncRun.builder()
                .id(100L)
                .syncType("daily_quote")
                .status("success")
                .build()), 1L);
        when(syncService.pageSyncRuns(any(MarketDataSyncRunQueryDto.class))).thenReturn(page);

        MarketDataController controller = controller(syncService, mock(TradeCalendarService.class));

        PageResult<MarketDataSyncRun> response = controller.pageSyncRuns(new MarketDataSyncRunQueryDto());

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getTotal()).isEqualTo(1);
    }

    @Test
    void queryTradeCalendarKeepsPageResultContract() {
        TradeCalendarService tradeCalendarService = mock(TradeCalendarService.class);
        PageResult<TradeCalendar> page = PageResult.getDataTable(List.of(TradeCalendar.builder()
                .market("CN")
                .tradeDate(LocalDate.of(2026, 7, 6))
                .open(true)
                .build()), 1L);
        when(tradeCalendarService.pageTradeCalendars(any(TradeCalendarQueryDto.class))).thenReturn(page);

        MarketDataController controller = controller(mock(MarketDataSyncService.class), tradeCalendarService);

        PageResult<TradeCalendar> response = controller.pageTradeCalendar(new TradeCalendarQueryDto());

        assertThat(response.getCode()).isEqualTo(200);
        assertThat(response.getRows().getFirst().getMarket()).isEqualTo("CN");
    }

    private MarketDataController controller(MarketDataSyncService syncService, TradeCalendarService tradeCalendarService) {
        return new MarketDataController(
                mock(StockBaseService.class),
                mock(StockDailyQuoteService.class),
                new MockMarketDataProvider(),
                syncService,
                tradeCalendarService
        );
    }
}
