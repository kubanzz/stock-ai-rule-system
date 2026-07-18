package com.jx.tracker.risk.data.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AKTools/AKShare 生产适配器。每个数据集显式映射官方字段、对象层级与 point-in-time，
 * 不以请求参数或抓取时间填补缺失事实。
 */
public final class AkToolsFlowEventSourceClient implements FlowEventSourceClient {

    public static final String SOURCE = "aktools/akshare";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final RiskObjectKey CN_A = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final Map<FlowEventDataset, String> ENDPOINTS = Map.of(
            FlowEventDataset.MARGIN_FINANCING, "/api/public/stock_margin_account_info",
            FlowEventDataset.ETF_FUND_FLOW, "/api/public/fund_etf_spot_em",
            FlowEventDataset.EARNINGS_FORECAST, "/api/public/stock_yjyg_em",
            FlowEventDataset.STOCK_ANNOUNCEMENT, "/api/public/stock_zh_a_disclosure_report_cninfo",
            FlowEventDataset.SHARE_UNLOCK, "/api/public/stock_restricted_release_queue_sina",
            FlowEventDataset.SHARE_REDUCTION, "/api/public/stock_ggcg_em");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AkToolsFlowEventSourceClient(
            String baseUrl,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalArgumentException("AKTools baseUrl must not be blank");
        }
        this.restClient = restClientBuilder.baseUrl(baseUrl.trim()).build();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        try {
            request.dataset().validateObjects(request.objects());
            if (request.dataset() == FlowEventDataset.EARNINGS_FORECAST) {
                return fetchEarningsForecasts(request, fetchedAt);
            }
            if ((request.dataset() == FlowEventDataset.STOCK_ANNOUNCEMENT
                    || request.dataset() == FlowEventDataset.SHARE_UNLOCK)
                    && request.objects().size() > 1) {
                return fetchPerStock(request, fetchedAt);
            }
            return parseResponse(request, get(request, null), fetchedAt);
        } catch (RuntimeException exception) {
            return FlowEventSourceBatch.unavailable(
                    SOURCE, "AKTools request failed: " + rootMessage(exception), fetchedAt);
        }
    }

    private FlowEventSourceBatch fetchEarningsForecasts(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt
    ) {
        List<LocalDate> reportDates = reportQuarterEndsForAvailabilityWindow(
                request.startDate(), request.endDate());
        if (reportDates.isEmpty()) {
            return FlowEventSourceBatch.validZero(
                    SOURCE, boundaryCursor(request.endDate()), fetchedAt);
        }
        List<FlowEventSourceRecord> records = new ArrayList<>();
        LocalDate earliest = null;
        for (LocalDate reportDate : reportDates) {
            FlowEventSourceBatch quarter = parseResponse(request, get(request, reportDate), fetchedAt);
            if (quarter.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE
                    || quarter.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
                return quarter;
            }
            records.addAll(quarter.records());
            earliest = earlier(earliest, quarter.earliestAvailableDate());
        }
        if (records.isEmpty()) {
            return FlowEventSourceBatch.validZero(SOURCE, boundaryCursor(request.endDate()), fetchedAt);
        }
        return new FlowEventSourceBatch(
                SOURCE, deduplicate(records), RiskDataQualityStatus.AVAILABLE, null,
                maxRecordCursor(records), earlier(earliest, earliestTradeDate(records)),
                true, fetchedAt, null);
    }

    private FlowEventSourceBatch fetchPerStock(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt
    ) {
        List<RiskObjectKey> objects = request.objects().stream()
                .sorted(Comparator.comparing(RiskObjectKey::objectId))
                .toList();
        List<FlowEventSourceRecord> records = new ArrayList<>();
        LocalDate earliest = null;
        boolean historyComplete = true;
        for (RiskObjectKey object : objects) {
            FlowEventSourceRequest singleRequest = new FlowEventSourceRequest(
                    request.dataset(),
                    new RiskProviderRequest(
                            List.of(object), request.horizons(), request.startDate(), request.endDate(),
                            request.checkpoint()));
            FlowEventSourceBatch batch = parseResponse(singleRequest, get(singleRequest, null), fetchedAt);
            if (batch.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE
                    || batch.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
                return batch;
            }
            records.addAll(batch.records());
            earliest = earlier(earliest, batch.earliestAvailableDate());
            historyComplete = historyComplete && batch.historyComplete();
        }
        List<FlowEventSourceRecord> uniqueRecords = deduplicate(records);
        if (uniqueRecords.isEmpty()) {
            return new FlowEventSourceBatch(
                    SOURCE, List.of(), RiskDataQualityStatus.VALID_ZERO, null,
                    boundaryCursor(request.endDate()), earliest, historyComplete, fetchedAt, null);
        }
        return new FlowEventSourceBatch(
                SOURCE, uniqueRecords, RiskDataQualityStatus.AVAILABLE, null,
                maxRecordCursor(uniqueRecords), earlier(earliest, earliestTradeDate(uniqueRecords)),
                historyComplete, fetchedAt, null);
    }

    private String get(FlowEventSourceRequest request, LocalDate reportDate) {
        return restClient.get().uri(builder -> sourceUri(builder, request, reportDate))
                .retrieve().body(String.class);
    }

    private URI sourceUri(UriBuilder builder, FlowEventSourceRequest request, LocalDate reportDate) {
        builder.path(ENDPOINTS.get(request.dataset()));
        switch (request.dataset()) {
            case EARNINGS_FORECAST -> builder.queryParam(
                    "date", BASIC_DATE.format(reportDate == null ? request.endDate() : reportDate));
            case STOCK_ANNOUNCEMENT -> builder
                    .queryParam("symbol", sourceSymbol(request))
                    .queryParam("start_date", BASIC_DATE.format(request.startDate()))
                    .queryParam("end_date", BASIC_DATE.format(request.endDate()));
            case SHARE_UNLOCK -> builder.queryParam("symbol", sourceSymbol(request));
            case MARGIN_FINANCING, ETF_FUND_FLOW, SHARE_REDUCTION -> {
                // 官方接口返回全市场表或当前快照，不接受日期参数。
            }
        }
        return builder.build();
    }

    private List<LocalDate> reportQuarterEndsForAvailabilityWindow(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> dates = new ArrayList<>();
        int currentQuarterEndMonth = ((startDate.getMonthValue() - 1) / 3 + 1) * 3;
        LocalDate firstReportEnd = LocalDate.of(startDate.getYear(), currentQuarterEndMonth, 1)
                .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        if (firstReportEnd.isAfter(startDate)) {
            firstReportEnd = firstReportEnd.minusMonths(3)
                    .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        }
        int endQuarterEndMonth = ((endDate.getMonthValue() - 1) / 3 + 1) * 3;
        LocalDate lastReportEnd = LocalDate.of(endDate.getYear(), endQuarterEndMonth, 1)
                .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
        for (LocalDate reportEnd = firstReportEnd;
             !reportEnd.isAfter(lastReportEnd);
             reportEnd = reportEnd.plusMonths(3)
                     .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth())) {
            dates.add(reportEnd);
        }
        return dates;
    }

    private String sourceSymbol(FlowEventSourceRequest request) {
        if (request.objects().size() != 1) {
            throw new IllegalArgumentException("single-symbol endpoint requires exactly one stock object");
        }
        return request.objects().getFirst().objectId().replaceFirst("\\.(SH|SZ|BJ)$", "");
    }

    private FlowEventSourceBatch parseResponse(
            FlowEventSourceRequest request,
            String response,
            LocalDateTime fetchedAt
    ) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode rows = root != null && root.isArray() ? root : root == null ? null : root.path("data");
            JsonNode meta = root != null && root.isObject() ? root.path("meta") : objectMapper.nullNode();
            if (rows == null || !rows.isArray()) {
                return FlowEventSourceBatch.unavailable(SOURCE, "AKTools response data is not an array", fetchedAt);
            }
            String nextCursor = text(meta, "nextCursor");
            LocalDate earliestAvailableDate = optionalDate(meta, "earliestAvailableDate");
            boolean historyComplete = meta.has("historyComplete")
                    ? meta.path("historyComplete").asBoolean()
                    : defaultHistoryComplete(request.dataset());
            if (meta.path("insufficientHistory").asBoolean(false)) {
                return FlowEventSourceBatch.insufficientHistory(
                        SOURCE,
                        textOrDefault(meta, "historyGapReason", "AKShare endpoint history is insufficient"),
                        earliestAvailableDate,
                        fetchedAt);
            }
            if (rows.isEmpty()) {
                String cursor = nextCursor == null ? boundaryCursor(request.endDate()) : nextCursor;
                if (request.dataset().eventDataset()) {
                    return new FlowEventSourceBatch(
                            SOURCE, List.of(), RiskDataQualityStatus.VALID_ZERO, null, cursor,
                            earliestAvailableDate, historyComplete, fetchedAt, null);
                }
                return FlowEventSourceBatch.insufficientHistory(
                        SOURCE, request.dataset().code() + " returned no numeric observations",
                        earliestAvailableDate, fetchedAt);
            }
            List<FlowEventSourceRecord> records = mapRows(request, rows);
            if (records.isEmpty()) {
                String cursor = nextCursor == null ? boundaryCursor(request.endDate()) : nextCursor;
                if (request.dataset().eventDataset()) {
                    return new FlowEventSourceBatch(
                            SOURCE, List.of(), RiskDataQualityStatus.VALID_ZERO, null, cursor,
                            earliestAvailableDate, historyComplete, fetchedAt, null);
                }
                return FlowEventSourceBatch.insufficientHistory(
                        SOURCE, request.dataset().code() + " has no observations in requested range",
                        earliestAvailableDate, fetchedAt);
            }
            return new FlowEventSourceBatch(
                    SOURCE, records, RiskDataQualityStatus.AVAILABLE, null,
                    nextCursor, earlier(earliestAvailableDate, earliestTradeDate(records)),
                    historyComplete, fetchedAt, null);
        } catch (PointInTimeException exception) {
            return FlowEventSourceBatch.insufficientHistory(
                    SOURCE, "point-in-time unavailable: " + exception.getMessage(), null, fetchedAt);
        } catch (MappingException exception) {
            return FlowEventSourceBatch.unavailable(
                    SOURCE, "AKTools response parse failed: " + exception.getMessage(), fetchedAt);
        } catch (Exception exception) {
            return FlowEventSourceBatch.unavailable(
                    SOURCE, "AKTools response parse failed: " + rootMessage(exception), fetchedAt);
        }
    }

    private List<FlowEventSourceRecord> mapRows(FlowEventSourceRequest request, JsonNode rows) {
        return switch (request.dataset()) {
            case MARGIN_FINANCING -> mapMargin(request, rows);
            case ETF_FUND_FLOW -> mapEtf(request, rows);
            case EARNINGS_FORECAST -> mapStocks(request, rows, this::mapForecast);
            case STOCK_ANNOUNCEMENT -> mapStocks(request, rows, this::mapAnnouncement);
            case SHARE_UNLOCK -> mapStocks(request, rows, this::mapUnlock);
            case SHARE_REDUCTION -> mapStocks(request, rows, this::mapReduction);
        };
    }

    private List<FlowEventSourceRecord> mapMargin(FlowEventSourceRequest request, JsonNode rows) {
        List<MarginPoint> points = new ArrayList<>();
        for (JsonNode row : rows) {
            LocalDate date = requiredDate(row, "日期", "tradeDate");
            BigDecimal balance = requiredDecimal(row, "融资余额(亿)", "融资余额", "value");
            points.add(new MarginPoint(date, balance));
        }
        points.sort(Comparator.comparing(MarginPoint::date));
        List<FlowEventSourceRecord> records = new ArrayList<>();
        BigDecimal previous = null;
        for (MarginPoint point : points) {
            Map<String, Object> attributes = new HashMap<>();
            if (previous != null) {
                attributes.put("previousBalance", previous);
                attributes.put("referenceBalance", previous);
            }
            if (!point.date().isBefore(request.startDate()) && !point.date().isAfter(request.endDate())) {
                LocalDateTime observedAt = point.date().atTime(15, 0);
                LocalDateTime availableAt = point.date().plusDays(1).atStartOfDay();
                String id = "margin_financing:CN-A:" + point.date();
                records.add(record(id, CN_A, point.date(), observedAt, observedAt, availableAt,
                        point.balance(), "balance", "融资融券余额", attributes));
            }
            previous = point.balance();
        }
        return records;
    }

    private List<FlowEventSourceRecord> mapEtf(FlowEventSourceRequest request, JsonNode rows) {
        Map<LocalDate, EtfAggregate> byDate = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            LocalDate date = requiredDate(row, "数据日期", "日期", "tradeDate");
            if (date.isBefore(request.startDate()) || date.isAfter(request.endDate())) {
                continue;
            }
            BigDecimal netFlow = requiredDecimal(row, "主力净流入-净额", "净流入", "value");
            BigDecimal referenceAssets = requiredDecimal(row, "流通市值", "总市值", "referenceAssets");
            LocalDateTime updateTime = requiredUpdateDateTime(
                    firstText(row, "更新时间", "updateTime"), date, "更新时间");
            EtfAggregate aggregate = byDate.computeIfAbsent(date, ignored -> new EtfAggregate());
            aggregate.netFlow = aggregate.netFlow.add(netFlow);
            aggregate.referenceAssets = aggregate.referenceAssets.add(referenceAssets);
            aggregate.observedAt = later(aggregate.observedAt, updateTime);
        }
        List<FlowEventSourceRecord> records = new ArrayList<>();
        byDate.forEach((date, aggregate) -> {
            Map<String, Object> attributes = Map.of("referenceAssets", aggregate.referenceAssets);
            String id = "etf_fund_flow:CN-A:" + date;
            LocalDateTime conservativeAvailability = later(
                    date.plusDays(1).atStartOfDay(), aggregate.observedAt);
            records.add(record(id, CN_A, date, aggregate.observedAt, aggregate.observedAt,
                    conservativeAvailability, aggregate.netFlow, "net_flow",
                    "ETF 主力净流入", attributes));
        });
        return records;
    }

    private List<FlowEventSourceRecord> mapStocks(
            FlowEventSourceRequest request,
            JsonNode rows,
            StockRowMapper mapper
    ) {
        Set<RiskObjectKey> requested = Set.copyOf(request.objects());
        List<FlowEventSourceRecord> records = new ArrayList<>();
        for (JsonNode row : rows) {
            String rawCode = firstText(row, "股票代码", "代码", "objectId");
            if (!StringUtils.hasText(rawCode)) {
                throw new MappingException("股票代码/代码 missing");
            }
            RiskObjectKey object = new RiskObjectKey(RiskObjectType.STOCK, normalizeStockCode(rawCode));
            if (!requested.contains(object)) {
                continue;
            }
            FlowEventSourceRecord record = mapper.map(object, row);
            if (inRequestedFactWindow(request, record)) {
                records.add(record);
            }
        }
        return records;
    }

    private FlowEventSourceRecord mapForecast(RiskObjectKey object, JsonNode row) {
        LocalDate announcementDate = requiredDate(row, "公告日期", "announcementDate");
        BigDecimal change = requiredDecimal(row, "业绩变动幅度(%)", "业绩变动幅度", "value");
        String forecastType = textOrDefault(row, "预告类型", "");
        boolean adverse = change.signum() < 0 || containsAny(forecastType, "预减", "首亏", "续亏", "略减");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("economicMeaning", "cash_flow");
        attributes.put("announcementCategory", "earnings_warning");
        attributes.put("forecastType", forecastType);
        attributes.put("adverse", adverse);
        LocalDateTime observedAt = announcementDate.atStartOfDay();
        String id = "earnings_forecast:" + object.objectId() + ":" + announcementDate;
        return record(id, object, announcementDate, observedAt, observedAt,
                announcementDate.plusDays(1).atStartOfDay(), change, "forecast_change",
                forecastType, attributes);
    }

    private FlowEventSourceRecord mapAnnouncement(RiskObjectKey object, JsonNode row) {
        String rawTime = firstText(row, "公告时间", "observedAt");
        if (!StringUtils.hasText(rawTime)) {
            throw new PointInTimeException("公告时间 missing");
        }
        ParsedAvailability published = publishedAt(rawTime, "公告时间");
        String title = requiredText(row, "公告标题", "title");
        String meaning = announcementMeaning(title);
        Map<String, Object> attributes = new HashMap<>();
        if (meaning != null) {
            attributes.put("economicMeaning", meaning);
            attributes.put("adverse", true);
        }
        BigDecimal severity = optionalDecimal(row, "风险分值", "severity", "value");
        String id = textOrDefault(row, "公告编号",
                "stock_announcement:" + object.objectId() + ":" + published.observedAt() + ":" + title.hashCode());
        return record(id, object, published.observedAt().toLocalDate(), published.observedAt(),
                published.observedAt(), published.availableAt(), severity, "notice", title, attributes);
    }

    private FlowEventSourceRecord mapUnlock(RiskObjectKey object, JsonNode row) {
        LocalDate unlockDate = requiredDate(row, "解禁日期", "occurredAt");
        LocalDate announcementDate = requiredDate(row, "公告日期", "announcementDate");
        BigDecimal quantity = requiredDecimal(row, "解禁数量", "value");
        Map<String, Object> attributes = Map.of("scheduled", unlockDate.isAfter(announcementDate), "adverse", true);
        LocalDateTime observedAt = announcementDate.atStartOfDay();
        String id = "share_unlock:" + object.objectId() + ":" + unlockDate + ":" + announcementDate;
        return record(id, object, unlockDate, unlockDate.atTime(9, 30), observedAt,
                announcementDate.plusDays(1).atStartOfDay(), quantity, "share_unlock",
                "限售股解禁", attributes);
    }

    private FlowEventSourceRecord mapReduction(RiskObjectKey object, JsonNode row) {
        String direction = requiredText(row, "持股变动信息-增减", "direction");
        LocalDate announcementDate = requiredDate(row, "公告日", "公告日期", "announcementDate");
        LocalDate changeDate = requiredDate(row, "变动截止日", "变动开始日", "occurredAt");
        BigDecimal quantity = requiredDecimal(row, "持股变动信息-变动数量", "变动数量", "value");
        boolean reduction = direction.contains("减");
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("actualReduction", reduction);
        attributes.put("adverse", reduction);
        attributes.put("direction", direction);
        LocalDateTime observedAt = announcementDate.atStartOfDay();
        String id = "share_reduction:" + object.objectId() + ":" + changeDate + ":" + announcementDate;
        return record(id, object, changeDate, changeDate.atTime(15, 0), observedAt,
                announcementDate.plusDays(1).atStartOfDay(), quantity.abs(), "share_reduction",
                direction, attributes);
    }

    private FlowEventSourceRecord record(
            String id,
            RiskObjectKey object,
            LocalDate tradeDate,
            LocalDateTime occurredAt,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            BigDecimal value,
            String eventCode,
            String title,
            Map<String, Object> attributes
    ) {
        return new FlowEventSourceRecord(
                id, availableAt + "|" + id, object, tradeDate, occurredAt, observedAt, availableAt,
                value, unit(eventCode), eventCode, title, attributes);
    }

    private boolean inRequestedFactWindow(FlowEventSourceRequest request, FlowEventSourceRecord record) {
        if (record.availableAt().toLocalDate().isBefore(request.startDate())
                || record.availableAt().toLocalDate().isAfter(request.endDate())) {
            return false;
        }
        if (request.dataset() == FlowEventDataset.SHARE_UNLOCK) {
            return true;
        }
        return !record.tradeDate().isBefore(request.startDate()) && !record.tradeDate().isAfter(request.endDate());
    }

    private boolean defaultHistoryComplete(FlowEventDataset dataset) {
        return dataset != FlowEventDataset.ETF_FUND_FLOW
                && dataset != FlowEventDataset.SHARE_UNLOCK
                && dataset != FlowEventDataset.SHARE_REDUCTION;
    }

    private List<FlowEventSourceRecord> deduplicate(List<FlowEventSourceRecord> records) {
        Map<String, FlowEventSourceRecord> unique = new LinkedHashMap<>();
        records.forEach(record -> unique.putIfAbsent(
                record.object().objectId() + ":" + record.recordId() + ":" + record.availableAt(), record));
        return new ArrayList<>(unique.values());
    }

    private String normalizeStockCode(String rawCode) {
        String trimmed = rawCode.trim().toUpperCase();
        if (trimmed.matches("\\d{6}\\.(SH|SZ|BJ)")) {
            return trimmed;
        }
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (!digits.matches("\\d{6}")) {
            throw new MappingException("invalid stock code: " + rawCode);
        }
        String suffix = switch (digits.charAt(0)) {
            case '6' -> "SH";
            case '0', '3' -> "SZ";
            case '4', '8', '9' -> "BJ";
            default -> throw new MappingException("unsupported stock code: " + rawCode);
        };
        return digits + "." + suffix;
    }

    private BigDecimal requiredDecimal(JsonNode node, String... fields) {
        BigDecimal value = optionalDecimal(node, fields);
        if (value == null) {
            throw new MappingException(String.join("/", fields) + " missing");
        }
        return value;
    }

    private BigDecimal optionalDecimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isMissingNode() || value.isNull() || !StringUtils.hasText(value.asText())) {
                continue;
            }
            if (value.isNumber()) {
                return value.decimalValue();
            }
            try {
                return new BigDecimal(value.asText().replace(",", "").replace("%", "").trim());
            } catch (NumberFormatException exception) {
                throw new MappingException(field + " invalid numeric value: " + value.asText());
            }
        }
        return null;
    }

    private LocalDate requiredDate(JsonNode node, String... fields) {
        String value = firstText(node, fields);
        if (!StringUtils.hasText(value)) {
            throw new PointInTimeException(String.join("/", fields) + " missing");
        }
        try {
            return parseDate(value);
        } catch (RuntimeException exception) {
            throw new PointInTimeException(String.join("/", fields) + " invalid date: " + value);
        }
    }

    private LocalDate optionalDate(JsonNode node, String... fields) {
        String value = firstText(node, fields);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return parseDate(value);
        } catch (RuntimeException exception) {
            throw new PointInTimeException(String.join("/", fields) + " invalid date: " + value);
        }
    }

    private LocalDate parseDate(String value) {
        String normalized = value.trim();
        if (normalized.length() >= 10) {
            normalized = normalized.substring(0, 10);
        }
        return normalized.matches("\\d{8}")
                ? LocalDate.parse(normalized, BASIC_DATE)
                : LocalDate.parse(normalized);
    }

    private ParsedAvailability publishedAt(String value, String field) {
        String normalized = value.trim();
        try {
            if (normalized.length() == 8 || normalized.length() == 10) {
                LocalDate date = parseDate(normalized);
                return new ParsedAvailability(date.atStartOfDay(), date.plusDays(1).atStartOfDay());
            }
            LocalDateTime observedAt = LocalDateTime.parse(normalized.replace(' ', 'T'));
            return new ParsedAvailability(observedAt, observedAt);
        } catch (RuntimeException exception) {
            throw new PointInTimeException(field + " invalid datetime: " + value);
        }
    }

    private LocalDateTime requiredUpdateDateTime(String value, LocalDate dataDate, String field) {
        if (!StringUtils.hasText(value)) {
            throw new PointInTimeException(field + " missing");
        }
        String normalized = value.trim().replace(' ', 'T');
        try {
            return dataDate.atTime(LocalTime.parse(normalized));
        } catch (java.time.format.DateTimeParseException ignored) {
            // 继续尝试官方完整 datetime 格式。
        }
        try {
            return OffsetDateTime.parse(normalized)
                    .atZoneSameInstant(clock.getZone())
                    .toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
            // 继续尝试带区域或无偏移的 ISO datetime。
        }
        try {
            return ZonedDateTime.parse(normalized)
                    .withZoneSameInstant(clock.getZone())
                    .toLocalDateTime();
        } catch (java.time.format.DateTimeParseException ignored) {
            // 继续尝试本地 datetime。
        }
        try {
            return LocalDateTime.parse(normalized);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new PointInTimeException(field + " invalid time: " + value);
        }
    }

    private String requiredText(JsonNode node, String... fields) {
        String value = firstText(node, fields);
        if (!StringUtils.hasText(value)) {
            throw new MappingException(String.join("/", fields) + " missing");
        }
        return value;
    }

    private String announcementMeaning(String title) {
        if (containsAny(title, "业绩", "预亏", "亏损")) {
            return "cash_flow";
        }
        if (containsAny(title, "利率", "折现率")) {
            return "discount_rate";
        }
        if (containsAny(title, "融资", "债务", "授信", "质押")) {
            return "financing_conditions";
        }
        if (containsAny(title, "立案", "处罚", "退市", "诉讼", "风险提示")) {
            return "market_trust";
        }
        return null;
    }

    private boolean containsAny(String value, String... candidates) {
        if (value == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String unit(String eventCode) {
        return switch (eventCode) {
            case "balance" -> "amount";
            case "net_flow" -> "amount";
            case "forecast_change" -> "percent";
            case "share_unlock", "share_reduction" -> "shares";
            default -> "score";
        };
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value == null || value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String textOrDefault(JsonNode node, String field, String defaultValue) {
        String value = text(node, field);
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private LocalDate earliestTradeDate(List<FlowEventSourceRecord> records) {
        return records.stream().map(FlowEventSourceRecord::tradeDate).min(LocalDate::compareTo).orElse(null);
    }

    private LocalDate earlier(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private LocalDateTime later(LocalDateTime left, LocalDateTime right) {
        if (left == null) {
            return right;
        }
        return left.isAfter(right) ? left : right;
    }

    private String maxRecordCursor(List<FlowEventSourceRecord> records) {
        return records.stream().map(FlowEventSourceRecord::cursor).max(String::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("records must not be empty"));
    }

    private String boundaryCursor(LocalDate endDate) {
        return endDate.atTime(LocalTime.MAX) + "|~";
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record MarginPoint(LocalDate date, BigDecimal balance) {
    }

    private record ParsedAvailability(LocalDateTime observedAt, LocalDateTime availableAt) {
    }

    private static final class EtfAggregate {
        private BigDecimal netFlow = BigDecimal.ZERO;
        private BigDecimal referenceAssets = BigDecimal.ZERO;
        private LocalDateTime observedAt;
    }

    @FunctionalInterface
    private interface StockRowMapper {
        FlowEventSourceRecord map(RiskObjectKey object, JsonNode row);
    }

    private static final class MappingException extends RuntimeException {
        private MappingException(String message) {
            super(message);
        }
    }

    private static final class PointInTimeException extends RuntimeException {
        private PointInTimeException(String message) {
            super(message);
        }
    }
}
