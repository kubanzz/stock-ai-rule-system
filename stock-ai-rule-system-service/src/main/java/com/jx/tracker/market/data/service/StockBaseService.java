package com.jx.tracker.market.data.service;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockBaseQueryDto;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;

import java.util.Collection;

public interface StockBaseService {

    MarketDataImportResultDto<StockBaseUpsertDto> upsertStockBases(Collection<StockBaseUpsertDto> rows);

    PageResult<StockBase> pageStocks(StockBaseQueryDto query);

    StockBase getBySymbolAndMarket(String symbol, String market);
}
