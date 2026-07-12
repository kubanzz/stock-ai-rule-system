package com.jx.tracker.market.data.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class AkToolsMarketDataProvider implements MarketDataProvider {

    public static final String DATA_SOURCE = "aktools/akshare";
    private static final String A_SHARE_SPOT_PATH = "/api/public/stock_zh_a_spot_em";
    private static final String INDEX_DAILY_PATH = "/api/public/stock_zh_index_daily_em";
    private static final String TRADE_CALENDAR_PATH = "/api/public/tool_trade_date_hist_sina";
    private static final String HS300_SYMBOL = "000300.SH";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AkToolsMarketDataProvider(
            String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new ServiceException("AKTools base URL 不能为空");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl.trim()).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<StockBaseUpsertDto> fetchStockList() {
        JsonNode rows = getArray(A_SHARE_SPOT_PATH);
        List<StockBaseUpsertDto> result = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            String symbol = normalizeAShare(text(row, "代码"));
            if (!StringUtils.hasText(symbol)) {
                continue;
            }
            StockBaseUpsertDto dto = new StockBaseUpsertDto();
            dto.setSymbol(symbol);
            dto.setName(text(row, "名称"));
            dto.setMarket(SymbolNormalizer.parseMarket(symbol));
            dto.setExchange(SymbolNormalizer.parseExchange(symbol));
            dto.setStatus("active");
            dto.setDataSource(DATA_SOURCE);
            dto.setLastSyncTime(LocalDateTime.now());
            result.add(dto);
        }
        return requireRows(result, "A 股股票列表");
    }

    @Override
    public List<StockDailyQuoteUpsertDto> fetchDailyQuotes(
            String symbol,
            LocalDate startDate,
            LocalDate endDate) {
        String normalizedSymbol = SymbolNormalizer.normalize(symbol);
        if (HS300_SYMBOL.equals(normalizedSymbol)) {
            return fetchHs300Quotes(startDate, endDate);
        }
        if (StringUtils.hasText(normalizedSymbol)) {
            throw new ServiceException("AKTools 一期仅支持全 A 股快照或沪深 300：" + normalizedSymbol);
        }
        LocalDate tradeDate = endDate != null ? endDate : startDate;
        if (tradeDate == null) {
            throw new ServiceException("全市场收盘快照必须指定交易日");
        }
        JsonNode rows = getArray(A_SHARE_SPOT_PATH);
        List<StockDailyQuoteUpsertDto> result = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            String rowSymbol = normalizeAShare(text(row, "代码"));
            if (!StringUtils.hasText(rowSymbol)) {
                continue;
            }
            StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
            dto.setSymbol(rowSymbol);
            dto.setTradeDate(tradeDate);
            dto.setOpenPrice(decimal(row, "今开"));
            dto.setHighPrice(decimal(row, "最高"));
            dto.setLowPrice(decimal(row, "最低"));
            dto.setClosePrice(decimal(row, "最新价"));
            dto.setPreClose(decimal(row, "昨收"));
            dto.setChangePct(decimal(row, "涨跌幅"));
            dto.setVolume(decimal(row, "成交量"));
            dto.setAmount(decimal(row, "成交额"));
            dto.setDataSource(DATA_SOURCE);
            dto.setSyncTime(LocalDateTime.now());
            result.add(dto);
        }
        return requireRows(result, "A 股收盘快照");
    }

    @Override
    public List<TradeCalendarDto> fetchTradeCalendar(LocalDate startDate, LocalDate endDate) {
        JsonNode rows = getArray(TRADE_CALENDAR_PATH);
        List<TradeCalendarDto> result = new ArrayList<>();
        for (JsonNode row : rows) {
            LocalDate tradeDate = parseDate(text(row, "trade_date"));
            if (tradeDate == null
                    || startDate != null && tradeDate.isBefore(startDate)
                    || endDate != null && tradeDate.isAfter(endDate)) {
                continue;
            }
            TradeCalendarDto dto = new TradeCalendarDto();
            dto.setMarket("CN");
            dto.setTradeDate(tradeDate);
            dto.setOpen(true);
            dto.setDataSource(DATA_SOURCE);
            dto.setSyncTime(LocalDateTime.now());
            result.add(dto);
        }
        result.sort(Comparator.comparing(TradeCalendarDto::getTradeDate));
        for (int index = 0; index < result.size(); index++) {
            TradeCalendarDto current = result.get(index);
            current.setPreTradeDate(index == 0 ? null : result.get(index - 1).getTradeDate());
            current.setNextTradeDate(index + 1 >= result.size() ? null : result.get(index + 1).getTradeDate());
        }
        return requireRows(result, "A 股交易日历");
    }

    private List<StockDailyQuoteUpsertDto> fetchHs300Quotes(LocalDate startDate, LocalDate endDate) {
        JsonNode rows = getArray(INDEX_DAILY_PATH, "sh000300", startDate, endDate);
        List<StockDailyQuoteUpsertDto> result = new ArrayList<>(rows.size());
        for (JsonNode row : rows) {
            LocalDate tradeDate = parseDate(firstText(row, "date", "日期"));
            if (tradeDate == null) {
                continue;
            }
            StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
            dto.setSymbol(HS300_SYMBOL);
            dto.setTradeDate(tradeDate);
            dto.setOpenPrice(firstDecimal(row, "open", "开盘"));
            dto.setHighPrice(firstDecimal(row, "high", "最高"));
            dto.setLowPrice(firstDecimal(row, "low", "最低"));
            dto.setClosePrice(firstDecimal(row, "close", "收盘"));
            dto.setVolume(firstDecimal(row, "volume", "成交量"));
            dto.setAmount(firstDecimal(row, "amount", "成交额"));
            dto.setDataSource(DATA_SOURCE);
            dto.setSyncTime(LocalDateTime.now());
            result.add(dto);
        }
        result.sort(Comparator.comparing(StockDailyQuoteUpsertDto::getTradeDate));
        for (int index = 1; index < result.size(); index++) {
            StockDailyQuoteUpsertDto current = result.get(index);
            BigDecimal previousClose = result.get(index - 1).getClosePrice();
            current.setPreClose(previousClose);
            if (current.getClosePrice() != null && previousClose != null && previousClose.signum() != 0) {
                current.setChangePct(current.getClosePrice().subtract(previousClose)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(previousClose, 6, RoundingMode.HALF_UP));
            }
        }
        return requireRows(result, "沪深 300 日线");
    }

    private JsonNode getArray(String path) {
        return readArray(restClient.get().uri(path).retrieve().body(String.class), path);
    }

    private JsonNode getArray(String path, String symbol, LocalDate startDate, LocalDate endDate) {
        String response = restClient.get().uri(builder -> {
            builder.path(path).queryParam("symbol", symbol);
            if (startDate != null) {
                builder.queryParam("start_date", startDate.format(BASIC_DATE));
            }
            if (endDate != null) {
                builder.queryParam("end_date", endDate.format(BASIC_DATE));
            }
            return builder.build();
        }).retrieve().body(String.class);
        return readArray(response, path);
    }

    private JsonNode readArray(String response, String operation) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode rows = root != null && root.isArray() ? root : root == null ? null : root.path("data");
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                throw new ServiceException("AKTools 返回空数据：" + operation);
            }
            return rows;
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("解析 AKTools 响应失败：" + operation, exception);
        }
    }

    private <T> List<T> requireRows(List<T> rows, String operation) {
        if (rows.isEmpty()) {
            throw new ServiceException("AKTools 返回空数据：" + operation);
        }
        return List.copyOf(rows);
    }

    private String normalizeAShare(String code) {
        if (!StringUtils.hasText(code) || !code.trim().matches("\\d{6}")) {
            return null;
        }
        return SymbolNormalizer.normalize(code);
    }

    private String firstText(JsonNode row, String... fields) {
        for (String field : fields) {
            String value = text(row, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal firstDecimal(JsonNode row, String... fields) {
        for (String field : fields) {
            BigDecimal value = decimal(row, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private BigDecimal decimal(JsonNode row, String field) {
        String value = text(row, field);
        if (!StringUtils.hasText(value) || "-".equals(value) || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 10) {
            return LocalDate.parse(normalized.substring(0, 10));
        }
        return LocalDate.parse(normalized, BASIC_DATE);
    }
}
