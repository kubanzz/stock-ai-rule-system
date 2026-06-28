package com.jx.tracker.market.data.provider;

import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;

import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {

    List<StockBaseUpsertDto> fetchStockList();

    default List<StockBaseUpsertDto> fetchStockBases() {
        return fetchStockList();
    }

    List<StockDailyQuoteUpsertDto> fetchDailyQuotes(String symbol, LocalDate startDate, LocalDate endDate);

    default List<TradeCalendarDto> fetchTradeCalendar(LocalDate startDate, LocalDate endDate) {
        return List.of();
    }
}
