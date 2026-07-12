package com.jx.tracker.market.data;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.market.data.service.impl.MarketDataBootstrapServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.SyncTaskExecutor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDataBootstrapServiceImplTest {

    @Test
    void queriesPersistedCnCalendarBeforeSyncingSnapshotAndBenchmark() {
        MarketDataSyncService syncService = mock(MarketDataSyncService.class);
        TradeCalendarService calendarService = mock(TradeCalendarService.class);
        MarketDataSyncResultDto success = new MarketDataSyncResultDto();
        success.setStatus("success");
        when(syncService.syncStockList(any())).thenReturn(success);
        when(syncService.syncTradeCalendar(any())).thenReturn(success);
        when(syncService.syncDailyQuotes(any())).thenReturn(success);
        TradeCalendar latest = TradeCalendar.builder()
                .market("CN")
                .open(true)
                .tradeDate(LocalDate.now().minusDays(1))
                .build();
        when(calendarService.pageTradeCalendars(any())).thenReturn(new PageResult<>(List.of(latest), 1));
        MarketDataBootstrapServiceImpl service = new MarketDataBootstrapServiceImpl(
                syncService, calendarService, new SyncTaskExecutor());

        service.start("A股", "test");

        ArgumentCaptor<TradeCalendarQueryDto> query = ArgumentCaptor.forClass(TradeCalendarQueryDto.class);
        verify(calendarService).pageTradeCalendars(query.capture());
        assertThat(query.getValue().getMarket()).isEqualTo("CN");
        verify(syncService, times(2)).syncDailyQuotes(any());
    }
}
