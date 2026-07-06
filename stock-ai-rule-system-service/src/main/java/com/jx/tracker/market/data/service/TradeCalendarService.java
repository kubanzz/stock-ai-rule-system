package com.jx.tracker.market.data.service;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;

import java.time.LocalDate;
import java.util.Collection;

public interface TradeCalendarService {

    MarketDataImportResultDto<TradeCalendarDto> upsertTradeCalendars(Collection<TradeCalendarDto> rows);

    PageResult<TradeCalendar> pageTradeCalendars(TradeCalendarQueryDto query);

    TradeCalendar getByMarketAndTradeDate(String market, LocalDate tradeDate);
}
