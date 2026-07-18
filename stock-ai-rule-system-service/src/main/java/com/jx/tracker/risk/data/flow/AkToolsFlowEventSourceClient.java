package com.jx.tracker.risk.data.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AKTools/AKShare 生产适配器。仅接收外部 baseUrl，不包含账号、Token 或回退假数据。
 * AKTools 侧建议把原始中英文字段规范化为本类读取的字段，以隔离上游字段漂移。
 */
public final class AkToolsFlowEventSourceClient implements FlowEventSourceClient {

    public static final String SOURCE = "aktools/akshare";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
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
            if (request.dataset() == FlowEventDataset.EARNINGS_FORECAST) {
                return fetchEarningsForecasts(request, fetchedAt);
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
        List<LocalDate> reportDates = completedQuarterEnds(request.startDate(), request.endDate());
        if (reportDates.isEmpty()) {
            return FlowEventSourceBatch.validZero(
                    SOURCE, request.dataset().code() + ":" + request.endDate(), fetchedAt);
        }
        List<FlowEventSourceRecord> records = new ArrayList<>();
        for (LocalDate reportDate : reportDates) {
            FlowEventSourceBatch quarter = parseResponse(request, get(request, reportDate), fetchedAt);
            if (quarter.qualityStatus() == RiskDataQualityStatus.UNAVAILABLE) {
                return quarter;
            }
            records.addAll(quarter.records());
        }
        if (records.isEmpty()) {
            return FlowEventSourceBatch.validZero(
                    SOURCE, "quarter:" + reportDates.getLast(), fetchedAt);
        }
        return new FlowEventSourceBatch(
                SOURCE, records, RiskDataQualityStatus.AVAILABLE, null,
                "quarter:" + reportDates.getLast(), reportDates.getFirst(), true, fetchedAt, null);
    }

    private String get(FlowEventSourceRequest request, LocalDate reportDate) {
        return restClient.get().uri(builder -> sourceUri(builder, request, reportDate))
                .retrieve().body(String.class);
    }

    private URI sourceUri(
            UriBuilder builder,
            FlowEventSourceRequest request,
            LocalDate reportDate
    ) {
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
                // 这些 AKShare 函数返回全表或当前快照，不接受日期参数。
            }
        }
        return builder.build();
    }

    private List<LocalDate> completedQuarterEnds(LocalDate startDate, LocalDate endDate) {
        List<LocalDate> dates = new ArrayList<>();
        for (int year = startDate.getYear(); year <= endDate.getYear(); year++) {
            for (int month : List.of(3, 6, 9, 12)) {
                LocalDate quarterEnd = LocalDate.of(year, month, 1)
                        .with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());
                if (!quarterEnd.isBefore(startDate) && !quarterEnd.isAfter(endDate)) {
                    dates.add(quarterEnd);
                }
            }
        }
        return dates;
    }

    private String sourceSymbol(FlowEventSourceRequest request) {
        if (request.objects().size() != 1) {
            return "";
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
            LocalDate earliestAvailableDate = parseDate(text(meta, "earliestAvailableDate"));
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
                return new FlowEventSourceBatch(
                        SOURCE, List.of(), RiskDataQualityStatus.VALID_ZERO, null,
                        nextCursor == null ? request.dataset().code() + ":" + request.endDate() : nextCursor,
                        earliestAvailableDate, historyComplete, fetchedAt, null);
            }
            List<FlowEventSourceRecord> records = new ArrayList<>(rows.size());
            for (JsonNode row : rows) {
                records.add(mapRow(request, row, fetchedAt));
            }
            return new FlowEventSourceBatch(
                    SOURCE, records, RiskDataQualityStatus.AVAILABLE, null,
                    nextCursor, earliestAvailableDate, historyComplete, fetchedAt, null);
        } catch (Exception exception) {
            return FlowEventSourceBatch.unavailable(
                    SOURCE, "AKTools response parse failed: " + rootMessage(exception), fetchedAt);
        }
    }

    private boolean defaultHistoryComplete(FlowEventDataset dataset) {
        return dataset != FlowEventDataset.ETF_FUND_FLOW
                && dataset != FlowEventDataset.EARNINGS_FORECAST;
    }

    private FlowEventSourceRecord mapRow(
            FlowEventSourceRequest request,
            JsonNode row,
            LocalDateTime fetchedAt
    ) {
        LocalDate tradeDate = parseDate(firstText(row, "tradeDate", "日期", "公告日期"));
        if (tradeDate == null) {
            tradeDate = request.endDate();
        }
        LocalDateTime observedAt = parseDateTime(firstText(row, "observedAt", "公告时间"), tradeDate, fetchedAt);
        LocalDateTime availableAt = parseDateTime(firstText(row, "availableAt", "发布时间"), tradeDate, observedAt);
        LocalDateTime occurredAt = parseDateTime(
                firstText(row, "occurredAt", "生效日期", "解禁日期"), tradeDate, tradeDate.atTime(15, 0));
        String objectId = firstText(row, "objectId", "股票代码", "代码");
        if (!StringUtils.hasText(objectId) && request.objects().size() == 1) {
            objectId = request.objects().getFirst().objectId();
        }
        String objectTypeCode = firstText(row, "objectType");
        RiskObjectType objectType = StringUtils.hasText(objectTypeCode)
                ? RiskObjectType.fromCode(objectTypeCode)
                : request.objects().size() == 1
                ? request.objects().getFirst().objectType()
                : RiskObjectType.STOCK;
        String eventCode = firstText(row, "eventCode", "事件类型", "公告类型");
        if (!StringUtils.hasText(eventCode)) {
            eventCode = request.dataset().code();
        }
        String recordId = firstText(row, "recordId", "公告编号", "id");
        if (!StringUtils.hasText(recordId)) {
            recordId = request.dataset().code() + ":" + objectId + ":" + tradeDate + ":" + eventCode;
        }
        String cursor = firstText(row, "cursor");
        if (!StringUtils.hasText(cursor)) {
            cursor = availableAt + ":" + recordId;
        }
        return new FlowEventSourceRecord(
                recordId, cursor, new RiskObjectKey(objectType, objectId), tradeDate,
                occurredAt, observedAt, availableAt,
                decimal(row, "value", "数值", "融资余额", "变动比例"),
                textOrDefault(row, "unit", "ratio"), eventCode,
                textOrDefault(row, "title", ""), attributes(row.path("attributes")));
    }

    private Map<String, Object> attributes(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<>() {
        });
    }

    private BigDecimal decimal(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                try {
                    return new BigDecimal(value.asText().replace(",", "").replace("%", ""));
                } catch (NumberFormatException ignored) {
                    // 尝试下一个候选字段。
                }
            }
        }
        return BigDecimal.ZERO;
    }

    private LocalDate parseDate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() >= 10) {
            normalized = normalized.substring(0, 10);
        }
        return normalized.matches("\\d{8}")
                ? LocalDate.parse(normalized, BASIC_DATE)
                : LocalDate.parse(normalized);
    }

    private LocalDateTime parseDateTime(
            String value,
            LocalDate fallbackDate,
            LocalDateTime fallback
    ) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim().replace(' ', 'T');
        if (normalized.length() == 10 || normalized.matches("\\d{8}")) {
            LocalDate parsedDate = parseDate(normalized);
            return parsedDate.atTime(LocalTime.NOON);
        }
        return LocalDateTime.parse(normalized);
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

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
