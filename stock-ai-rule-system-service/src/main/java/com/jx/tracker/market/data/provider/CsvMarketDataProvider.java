package com.jx.tracker.market.data.provider;

import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.util.MarketDataNormalizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CsvMarketDataProvider implements MarketDataProvider {

    private final List<StockBaseUpsertDto> stockBases;

    private final List<StockDailyQuoteUpsertDto> dailyQuotes;

    private final MarketDataImportResultDto<StockBaseUpsertDto> stockBaseResult;

    private final MarketDataImportResultDto<StockDailyQuoteUpsertDto> dailyQuoteResult;

    private CsvMarketDataProvider(
            List<StockBaseUpsertDto> stockBases,
            List<StockDailyQuoteUpsertDto> dailyQuotes,
            MarketDataImportResultDto<StockBaseUpsertDto> stockBaseResult,
            MarketDataImportResultDto<StockDailyQuoteUpsertDto> dailyQuoteResult) {
        this.stockBases = stockBases;
        this.dailyQuotes = dailyQuotes;
        this.stockBaseResult = stockBaseResult;
        this.dailyQuoteResult = dailyQuoteResult;
    }

    public static CsvMarketDataProvider fromStockBaseCsv(InputStream inputStream) {
        MarketDataImportResultDto<StockBaseUpsertDto> result = readStockBases(inputStream);
        return new CsvMarketDataProvider(result.getAcceptedRows(), List.of(), result, new MarketDataImportResultDto<>());
    }

    public static CsvMarketDataProvider fromDailyQuoteCsv(InputStream inputStream) {
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> result = readDailyQuotes(inputStream);
        return new CsvMarketDataProvider(List.of(), result.getAcceptedRows(), new MarketDataImportResultDto<>(), result);
    }

    @Override
    public List<StockBaseUpsertDto> fetchStockBases() {
        return stockBases;
    }

    @Override
    public List<StockDailyQuoteUpsertDto> fetchDailyQuotes(String symbol, LocalDate startDate, LocalDate endDate) {
        String normalizedSymbol = MarketDataNormalizer.normalizeCode(symbol);
        return dailyQuotes.stream()
                .filter(quote -> normalizedSymbol == null || normalizedSymbol.equals(quote.getSymbol()))
                .filter(quote -> startDate == null || !quote.getTradeDate().isBefore(startDate))
                .filter(quote -> endDate == null || !quote.getTradeDate().isAfter(endDate))
                .toList();
    }

    public MarketDataImportResultDto<StockBaseUpsertDto> importStockBases() {
        return stockBaseResult;
    }

    public MarketDataImportResultDto<StockDailyQuoteUpsertDto> importDailyQuotes() {
        return dailyQuoteResult;
    }

    private static MarketDataImportResultDto<StockBaseUpsertDto> readStockBases(InputStream inputStream) {
        MarketDataImportResultDto<StockBaseUpsertDto> result = new MarketDataImportResultDto<>();
        readRows(inputStream, (rowNumber, columns, header) -> {
            StockBaseUpsertDto dto = new StockBaseUpsertDto();
            dto.setSymbol(value(columns, header, "symbol"));
            dto.setName(value(columns, header, "name"));
            dto.setMarket(value(columns, header, "market"));
            dto.setIndustry(value(columns, header, "industry"));
            dto.setStatus(value(columns, header, "status"));
            String error = MarketDataNormalizer.validate(dto);
            if (error == null) {
                result.accept(dto);
            } else {
                result.reject(rowNumber, dto.getSymbol(), error);
            }
        }, result);
        return result;
    }

    private static MarketDataImportResultDto<StockDailyQuoteUpsertDto> readDailyQuotes(InputStream inputStream) {
        MarketDataImportResultDto<StockDailyQuoteUpsertDto> result = new MarketDataImportResultDto<>();
        readRows(inputStream, (rowNumber, columns, header) -> {
            StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
            dto.setSymbol(value(columns, header, "symbol"));
            dto.setTradeDate(parseDate(value(columns, header, "trade_date")));
            dto.setOpenPrice(parseDecimal(value(columns, header, "open_price")));
            dto.setHighPrice(parseDecimal(value(columns, header, "high_price")));
            dto.setLowPrice(parseDecimal(value(columns, header, "low_price")));
            dto.setClosePrice(parseDecimal(value(columns, header, "close_price")));
            dto.setVolume(parseDecimal(value(columns, header, "volume")));
            dto.setAmount(parseDecimal(value(columns, header, "amount")));
            dto.setChangePct(parseDecimal(value(columns, header, "change_pct")));
            String error = MarketDataNormalizer.validate(dto);
            if (error == null) {
                result.accept(dto);
            } else {
                result.reject(rowNumber, dto.getSymbol(), error);
            }
        }, result);
        return result;
    }

    private static void readRows(InputStream inputStream, CsvRowConsumer consumer, MarketDataImportResultDto<?> result) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return;
            }
            Map<String, Integer> header = parseHeader(headerLine);
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    consumer.accept(rowNumber, split(line), header);
                } catch (RuntimeException e) {
                    result.reject(rowNumber, null, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read csv file", e);
        }
    }

    private static Map<String, Integer> parseHeader(String headerLine) {
        String[] headers = split(headerLine.replace("\uFEFF", ""));
        Map<String, Integer> header = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            header.put(headers[i].trim().toLowerCase(), i);
        }
        return header;
    }

    private static String[] split(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                values.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString().trim());
        return values.toArray(String[]::new);
    }

    private static String value(String[] columns, Map<String, Integer> header, String columnName) {
        Integer index = header.get(columnName);
        if (index == null || index >= columns.length) {
            return null;
        }
        String value = columns[index].trim();
        return value.isEmpty() ? null : value;
    }

    private static LocalDate parseDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static BigDecimal parseDecimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @FunctionalInterface
    private interface CsvRowConsumer {
        void accept(int rowNumber, String[] columns, Map<String, Integer> header);
    }
}
