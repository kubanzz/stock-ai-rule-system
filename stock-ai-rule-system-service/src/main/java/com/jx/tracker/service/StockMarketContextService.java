package com.jx.tracker.service;

import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.vo.StockConsoleVo;

import java.time.LocalDate;
import java.util.List;

public interface StockMarketContextService {

    StockConsoleVo.MarketContext marketContext(String market, LocalDate tradeDate, List<StockBase> candidates);
}
