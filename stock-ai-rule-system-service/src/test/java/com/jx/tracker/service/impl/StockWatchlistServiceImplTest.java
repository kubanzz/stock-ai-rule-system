package com.jx.tracker.service.impl;

import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockWatchlistServiceImplTest {

    @Test
    void exposesWatchlistBusinessFields() {
        assertEquals("my-follow", StockWatchlist.builder().poolCode("my-follow").build().getPoolCode());
        assertEquals("600519.SH", StockWatchlistItem.builder().symbol("600519.SH").build().getSymbol());
    }
}
