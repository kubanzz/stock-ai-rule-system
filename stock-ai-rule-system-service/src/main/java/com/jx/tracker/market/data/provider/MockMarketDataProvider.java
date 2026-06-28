package com.jx.tracker.market.data.provider;

import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class MockMarketDataProvider implements MarketDataProvider {

    @Override
    public List<StockBaseUpsertDto> fetchStockList() {
        StockBaseUpsertDto pingAn = stock("000001.SZ", "平安银行", "银行", "active");
        StockBaseUpsertDto spdb = stock("600000.SH", "浦发银行", "银行", "active");
        return List.of(pingAn, spdb);
    }

    @Override
    public List<StockDailyQuoteUpsertDto> fetchDailyQuotes(String symbol, LocalDate startDate, LocalDate endDate) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? "000001.SZ" : SymbolNormalizer.normalize(symbol);
        LocalDate start = startDate == null ? LocalDate.of(2026, 6, 20) : startDate;
        LocalDate end = endDate == null ? start.plusDays(1) : endDate;
        return start.datesUntil(end.plusDays(1))
                .map(date -> quote(normalizedSymbol, date))
                .toList();
    }

    @Override
    public List<TradeCalendarDto> fetchTradeCalendar(LocalDate startDate, LocalDate endDate) {
        LocalDate start = startDate == null ? LocalDate.of(2026, 6, 20) : startDate;
        LocalDate end = endDate == null ? start.plusDays(1) : endDate;
        return start.datesUntil(end.plusDays(1))
                .map(date -> {
                    TradeCalendarDto dto = new TradeCalendarDto();
                    dto.setMarket("CN");
                    dto.setTradeDate(date);
                    dto.setOpen(true);
                    dto.setPreTradeDate(date.minusDays(1));
                    dto.setNextTradeDate(date.plusDays(1));
                    dto.setDataSource("mock");
                    dto.setSyncTime(LocalDateTime.now());
                    return dto;
                })
                .toList();
    }

    private StockBaseUpsertDto stock(String symbol, String name, String industry, String status) {
        StockBaseUpsertDto dto = new StockBaseUpsertDto();
        dto.setSymbol(symbol);
        dto.setName(name);
        dto.setMarket(SymbolNormalizer.parseMarket(symbol));
        dto.setExchange(SymbolNormalizer.parseExchange(symbol));
        dto.setIndustry(industry);
        dto.setStatus(status);
        dto.setDataSource("mock");
        dto.setLastSyncTime(LocalDateTime.now());
        return dto;
    }

    private StockDailyQuoteUpsertDto quote(String symbol, LocalDate tradeDate) {
        StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
        dto.setSymbol(symbol);
        dto.setTradeDate(tradeDate);
        dto.setOpenPrice(new BigDecimal("10.00"));
        dto.setHighPrice(new BigDecimal("10.80"));
        dto.setLowPrice(new BigDecimal("9.90"));
        dto.setClosePrice(new BigDecimal("10.50"));
        dto.setPreClose(new BigDecimal("10.00"));
        dto.setVolume(new BigDecimal("100000"));
        dto.setAmount(new BigDecimal("1050000"));
        dto.setChangePct(new BigDecimal("1.20"));
        dto.setDataSource("mock");
        dto.setSyncTime(LocalDateTime.now());
        return dto;
    }
}
