package com.jx.tracker.service;

import com.jx.tracker.domain.vo.StockConsoleVo;

import java.util.List;

public interface StockWatchlistService {

    List<StockConsoleVo.WatchlistPool> list(String market);

    StockConsoleVo.WatchlistPool create(StockConsoleVo.WatchlistMutationRequest request);

    StockConsoleVo.WatchlistPool update(String poolCode, StockConsoleVo.WatchlistMutationRequest request);

    void delete(String poolCode);

    StockConsoleVo.WatchlistPool addStock(
            String poolCode,
            StockConsoleVo.WatchlistStockMutationRequest request
    );

    StockConsoleVo.WatchlistPool removeStock(String poolCode, String symbol);
}
