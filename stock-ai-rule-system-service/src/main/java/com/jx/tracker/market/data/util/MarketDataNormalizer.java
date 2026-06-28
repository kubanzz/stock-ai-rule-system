package com.jx.tracker.market.data.util;

import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;

import java.math.BigDecimal;

public final class MarketDataNormalizer {

    private MarketDataNormalizer() {
    }

    public static String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized.toUpperCase();
    }

    public static String normalizeMarket(String value) {
        String normalized = normalizeCode(value);
        if ("SZ".equals(normalized) || "SH".equals(normalized)) {
            return "CN";
        }
        return normalized;
    }

    public static StockBaseUpsertDto normalize(StockBaseUpsertDto dto) {
        String originalMarket = dto.getMarket();
        String originalExchange = dto.getExchange();
        String normalizedSymbol = SymbolNormalizer.normalize(dto.getSymbol());
        dto.setSymbol(normalizedSymbol);
        String parsedMarket = SymbolNormalizer.parseMarket(normalizedSymbol);
        String parsedExchange = SymbolNormalizer.parseExchange(normalizedSymbol);
        dto.setMarket(parsedMarket == null ? normalizeMarket(originalMarket) : parsedMarket);
        dto.setExchange(parsedExchange == null ? normalizeCode(originalExchange) : parsedExchange);
        return dto;
    }

    public static StockDailyQuoteUpsertDto normalize(StockDailyQuoteUpsertDto dto) {
        dto.setSymbol(SymbolNormalizer.normalize(dto.getSymbol()));
        return dto;
    }

    public static String validate(StockBaseUpsertDto dto) {
        if (dto == null) {
            return "row is empty";
        }
        normalize(dto);
        if (dto.getSymbol() == null) {
            return "symbol is required";
        }
        if (dto.getMarket() == null) {
            return "market is required";
        }
        return null;
    }

    public static String validate(StockDailyQuoteUpsertDto dto) {
        if (dto == null) {
            return "row is empty";
        }
        normalize(dto);
        if (dto.getSymbol() == null) {
            return "symbol is required";
        }
        if (dto.getTradeDate() == null) {
            return "trade_date is required";
        }
        if (dto.getHighPrice() != null && dto.getLowPrice() != null
                && dto.getHighPrice().compareTo(dto.getLowPrice()) < 0) {
            return "high_price must be greater than or equal to low_price";
        }
        if (isNegative(dto.getOpenPrice()) || isNegative(dto.getHighPrice()) || isNegative(dto.getLowPrice())
                || isNegative(dto.getClosePrice()) || isNegative(dto.getVolume()) || isNegative(dto.getAmount())) {
            return "price, volume and amount cannot be negative";
        }
        if (dto.getClosePrice() != null && dto.getHighPrice() != null
                && dto.getClosePrice().compareTo(dto.getHighPrice()) > 0) {
            return "close_price cannot be greater than high_price";
        }
        if (dto.getClosePrice() != null && dto.getLowPrice() != null
                && dto.getClosePrice().compareTo(dto.getLowPrice()) < 0) {
            return "close_price cannot be less than low_price";
        }
        return null;
    }

    private static boolean isNegative(BigDecimal value) {
        return value != null && value.signum() < 0;
    }
}
