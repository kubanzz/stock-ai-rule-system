package com.jx.tracker.market.data.provider;

import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;

import java.time.LocalDate;
import java.util.List;

public interface MarketDataProvider {

    List<StockBaseUpsertDto> fetchStockBases();

    List<StockDailyQuoteUpsertDto> fetchDailyQuotes(String symbol, LocalDate startDate, LocalDate endDate);
}
