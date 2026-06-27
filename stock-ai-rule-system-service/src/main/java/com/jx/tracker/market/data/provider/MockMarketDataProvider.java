package com.jx.tracker.market.data.provider;

import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
public class MockMarketDataProvider implements MarketDataProvider {

    @Override
    public List<StockBaseUpsertDto> fetchStockBases() {
        StockBaseUpsertDto pingAn = stock("SZ000001", "平安银行", "SZ", "银行", "active");
        StockBaseUpsertDto spdb = stock("SH600000", "浦发银行", "SH", "银行", "active");
        return List.of(pingAn, spdb);
    }

    @Override
    public List<StockDailyQuoteUpsertDto> fetchDailyQuotes(String symbol, LocalDate startDate, LocalDate endDate) {
        String normalizedSymbol = symbol == null || symbol.isBlank() ? "SZ000001" : symbol.trim().toUpperCase();
        LocalDate start = startDate == null ? LocalDate.of(2026, 6, 20) : startDate;
        LocalDate end = endDate == null ? start.plusDays(1) : endDate;
        return start.datesUntil(end.plusDays(1))
                .map(date -> quote(normalizedSymbol, date))
                .toList();
    }

    private StockBaseUpsertDto stock(String symbol, String name, String market, String industry, String status) {
        StockBaseUpsertDto dto = new StockBaseUpsertDto();
        dto.setSymbol(symbol);
        dto.setName(name);
        dto.setMarket(market);
        dto.setIndustry(industry);
        dto.setStatus(status);
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
        dto.setVolume(new BigDecimal("100000"));
        dto.setAmount(new BigDecimal("1050000"));
        dto.setChangePct(new BigDecimal("1.20"));
        return dto;
    }
}
