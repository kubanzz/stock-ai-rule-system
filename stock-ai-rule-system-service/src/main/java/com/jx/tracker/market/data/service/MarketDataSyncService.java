package com.jx.tracker.market.data.service;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRunQueryDto;

public interface MarketDataSyncService {

    MarketDataSyncResultDto syncStockList(MarketDataSyncRequestDto request);

    MarketDataSyncResultDto syncDailyQuotes(DailyQuoteSyncRequestDto request);

    MarketDataSyncResultDto syncTradeCalendar(MarketDataSyncRequestDto request);

    PageResult<MarketDataSyncRun> pageSyncRuns(MarketDataSyncRunQueryDto query);
}
