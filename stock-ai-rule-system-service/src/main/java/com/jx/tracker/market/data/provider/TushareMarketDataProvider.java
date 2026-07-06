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
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TushareMarketDataProvider implements MarketDataProvider {

    private static final DateTimeFormatter TUSHARE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final String token;

    private final RestClient restClient;

    private final ObjectMapper objectMapper;

    public TushareMarketDataProvider(String token, String apiUrl, RestClient.Builder restClientBuilder, ObjectMapper objectMapper) {
        this.token = token == null ? null : token.trim();
        this.restClient = restClientBuilder.baseUrl(validateApiUrl(apiUrl)).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<StockBaseUpsertDto> fetchStockList() {
        JsonNode data = post("stock_basic", Map.of("list_status", "L"),
                "ts_code,name,industry,list_status,exchange");
        List<StockBaseUpsertDto> rows = new ArrayList<>();
        for (Map<String, String> row : rows(data)) {
            StockBaseUpsertDto dto = new StockBaseUpsertDto();
            dto.setSymbol(SymbolNormalizer.normalize(row.get("ts_code")));
            dto.setName(row.get("name"));
            dto.setMarket(SymbolNormalizer.parseMarket(dto.getSymbol()));
            dto.setExchange(StringUtils.hasText(row.get("exchange"))
                    ? row.get("exchange")
                    : SymbolNormalizer.parseExchange(dto.getSymbol()));
            dto.setIndustry(row.get("industry"));
            dto.setStatus("L".equals(row.get("list_status")) ? "active" : row.get("list_status"));
            dto.setDataSource("tushare");
            dto.setLastSyncTime(LocalDateTime.now());
            rows.add(dto);
        }
        return rows;
    }

    @Override
    public List<StockDailyQuoteUpsertDto> fetchDailyQuotes(String symbol, LocalDate startDate, LocalDate endDate) {
        if (!StringUtils.hasText(symbol)) {
            throw new ServiceException("Tushare 日 K 同步必须指定 targetSymbol");
        }
        Map<String, String> params = new HashMap<>();
        params.put("ts_code", SymbolNormalizer.normalize(symbol));
        if (startDate != null) {
            params.put("start_date", startDate.format(TUSHARE_DATE));
        }
        if (endDate != null) {
            params.put("end_date", endDate.format(TUSHARE_DATE));
        }

        JsonNode data = post("daily", params,
                "ts_code,trade_date,open,high,low,close,pre_close,pct_chg,vol,amount");
        List<StockDailyQuoteUpsertDto> rows = new ArrayList<>();
        for (Map<String, String> row : rows(data)) {
            StockDailyQuoteUpsertDto dto = new StockDailyQuoteUpsertDto();
            dto.setSymbol(SymbolNormalizer.normalize(row.get("ts_code")));
            dto.setTradeDate(parseDate(row.get("trade_date")));
            dto.setOpenPrice(parseDecimal(row.get("open")));
            dto.setHighPrice(parseDecimal(row.get("high")));
            dto.setLowPrice(parseDecimal(row.get("low")));
            dto.setClosePrice(parseDecimal(row.get("close")));
            dto.setPreClose(parseDecimal(row.get("pre_close")));
            dto.setChangePct(parseDecimal(row.get("pct_chg")));
            dto.setVolume(parseDecimal(row.get("vol")));
            dto.setAmount(parseDecimal(row.get("amount")));
            dto.setDataSource("tushare");
            dto.setSyncTime(LocalDateTime.now());
            rows.add(dto);
        }
        return rows;
    }

    private String validateApiUrl(String apiUrl) {
        if (!StringUtils.hasText(apiUrl)) {
            throw new ServiceException("Tushare provider API URL is required");
        }
        URI uri = URI.create(apiUrl.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if ("https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && isLocalHost(host))) {
            return apiUrl.trim();
        }
        throw new ServiceException("Tushare provider API URL must use HTTPS for remote hosts");
    }

    private boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    @Override
    public List<TradeCalendarDto> fetchTradeCalendar(LocalDate startDate, LocalDate endDate) {
        Map<String, String> params = new HashMap<>();
        params.put("exchange", "SSE");
        if (startDate != null) {
            params.put("start_date", startDate.format(TUSHARE_DATE));
        }
        if (endDate != null) {
            params.put("end_date", endDate.format(TUSHARE_DATE));
        }

        JsonNode data = post("trade_cal", params, "exchange,cal_date,is_open,pretrade_date");
        List<TradeCalendarDto> rows = new ArrayList<>();
        for (Map<String, String> row : rows(data)) {
            LocalDate tradeDate = parseDate(row.get("cal_date"));
            TradeCalendarDto dto = new TradeCalendarDto();
            dto.setMarket("CN");
            dto.setTradeDate(tradeDate);
            dto.setOpen("1".equals(row.get("is_open")));
            dto.setPreTradeDate(parseDate(row.get("pretrade_date")));
            dto.setDataSource("tushare");
            dto.setSyncTime(LocalDateTime.now());
            rows.add(dto);
        }
        populateNextTradeDates(rows);
        return rows;
    }

    private JsonNode post(String apiName, Map<String, String> params, String fields) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("api_name", apiName);
            body.put("token", token);
            body.put("params", params);
            body.put("fields", fields);
            String response = restClient.post()
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(response);
            int code = root.path("code").asInt(0);
            if (code != 0) {
                throw new ServiceException("Tushare provider failed: " + root.path("msg").asText("unknown error"));
            }
            return root.path("data");
        } catch (RuntimeException ex) {
            if (ex instanceof ServiceException serviceException) {
                throw serviceException;
            }
            throw new ServiceException("调用 Tushare 行情 Provider 失败", ex);
        } catch (Exception ex) {
            throw new ServiceException("解析 Tushare 行情 Provider 响应失败", ex);
        }
    }

    private List<Map<String, String>> rows(JsonNode data) {
        List<String> fields = new ArrayList<>();
        data.path("fields").forEach(field -> fields.add(field.asText()));
        List<Map<String, String>> rows = new ArrayList<>();
        data.path("items").forEach(item -> {
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < fields.size() && i < item.size(); i++) {
                row.put(fields.get(i), item.get(i).isNull() ? null : item.get(i).asText());
            }
            rows.add(row);
        });
        return rows;
    }

    private void populateNextTradeDates(List<TradeCalendarDto> rows) {
        rows.sort(Comparator.comparing(TradeCalendarDto::getTradeDate, Comparator.nullsLast(Comparator.naturalOrder())));
        List<LocalDate> openDates = rows.stream()
                .filter(TradeCalendarDto::isOpen)
                .map(TradeCalendarDto::getTradeDate)
                .filter(date -> date != null)
                .toList();
        for (TradeCalendarDto row : rows) {
            LocalDate tradeDate = row.getTradeDate();
            row.setNextTradeDate(tradeDate == null ? null : openDates.stream()
                    .filter(openDate -> openDate.isAfter(tradeDate))
                    .findFirst()
                    .orElse(null));
        }
    }

    private LocalDate parseDate(String value) {
        return StringUtils.hasText(value) ? LocalDate.parse(value, TUSHARE_DATE) : null;
    }

    private BigDecimal parseDecimal(String value) {
        return StringUtils.hasText(value) ? new BigDecimal(value) : null;
    }
}
