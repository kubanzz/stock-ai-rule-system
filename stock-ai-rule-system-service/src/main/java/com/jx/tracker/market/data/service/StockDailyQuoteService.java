package com.jx.tracker.market.data.service;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteQueryDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;

import java.time.LocalDate;
import java.util.Collection;

public interface StockDailyQuoteService {

    MarketDataImportResultDto<StockDailyQuoteUpsertDto> upsertDailyQuotes(Collection<StockDailyQuoteUpsertDto> rows);

    PageResult<StockDailyQuote> pageDailyQuotes(StockDailyQuoteQueryDto query);

    StockDailyQuote getBySymbolAndTradeDate(String symbol, LocalDate tradeDate);
}
