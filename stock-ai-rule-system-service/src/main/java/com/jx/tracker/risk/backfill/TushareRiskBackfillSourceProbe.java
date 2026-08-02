package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TuShare 风险回填权限探测器。每个 API 只请求代表性代码和最小日期窗口。
 */
public final class TushareRiskBackfillSourceProbe implements RiskBackfillSourceProbe {

    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String STOCK_CODE = "000001.SZ";
    private static final String ETF_CODE = "510300.SH";

    private final TushareRiskHttpClient httpClient;
    private final RiskBackfillSourceProbe fallbackProbe;

    public TushareRiskBackfillSourceProbe(
            TushareRiskHttpClient httpClient,
            RiskBackfillSourceProbe fallbackProbe
    ) {
        if (httpClient == null || fallbackProbe == null) {
            throw new IllegalArgumentException(
                    "TuShare and fallback source probes are required");
        }
        this.httpClient = httpClient;
        this.fallbackProbe = fallbackProbe;
    }

    @Override
    public SourceProbeResult probeTushare(LocalDate endDate) {
        if (endDate == null) {
            return SourceProbeResult.unreachable(
                    "tushare", "TuShare probe endDate is required");
        }
        List<SourceProbeSummary> summaries = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (ProbeSpec spec : specs(endDate)) {
            try {
                TushareRiskResponse response = httpClient.query(
                        new TushareRiskRequest(spec.api(), spec.params(), spec.fields()));
                if (response == null || !spec.api().equals(response.apiName())) {
                    failures.add(failure(spec.api(), "MAPPING", null));
                    continue;
                }
                List<String> requiredFields = List.of(spec.fields().split(","));
                if (!response.rows().isEmpty()
                        && !response.fields().containsAll(requiredFields)) {
                    failures.add(failure(spec.api(), "MAPPING", null));
                    continue;
                }
                summaries.add(summary(spec, response));
            } catch (TushareRiskException exception) {
                failures.add(failure(
                        spec.api(), exception.category().name(), exception.code()));
            } catch (ProbeMappingException exception) {
                failures.add(failure(spec.api(), "MAPPING", null));
            } catch (RuntimeException exception) {
                failures.add(failure(spec.api(), "NETWORK", null));
            }
        }
        if (!failures.isEmpty()) {
            return new SourceProbeResult(
                    "tushare", false,
                    "TuShare probe failed: " + String.join("; ", failures), summaries);
        }
        return SourceProbeResult.reachable("tushare", summaries);
    }

    @Override
    public SourceProbeResult probeAkTools(LocalDate endDate) {
        return fallbackProbe.probeAkTools(endDate);
    }

    @Override
    public SourceProbeResult probeDerivedGateway(LocalDate endDate) {
        return fallbackProbe.probeDerivedGateway(endDate);
    }

    private SourceProbeSummary summary(
            ProbeSpec spec,
            TushareRiskResponse response
    ) {
        LocalDate earliest = null;
        LocalDate latest = null;
        for (Map<String, Object> row : response.rows()) {
            for (String dateField : spec.dateFields()) {
                LocalDate date = parseDate(row.get(dateField));
                if (date == null) {
                    continue;
                }
                earliest = earliest == null || date.isBefore(earliest) ? date : earliest;
                latest = latest == null || date.isAfter(latest) ? date : latest;
            }
        }
        return new SourceProbeSummary(
                spec.api(), response.rows().size(), earliest, latest);
    }

    private LocalDate parseDate(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return text.length() == 8 && text.chars().allMatch(Character::isDigit)
                    ? LocalDate.parse(text, COMPACT_DATE)
                    : LocalDate.parse(text);
        } catch (DateTimeException exception) {
            throw new ProbeMappingException();
        }
    }

    private String failure(String api, String category, Integer code) {
        return "api=" + api + " category=" + category
                + (code == null ? "" : " code=" + code);
    }

    private List<ProbeSpec> specs(LocalDate endDate) {
        String end = compact(endDate);
        String start = compact(endDate.minusDays(7));
        Map<String, Object> stockWindow = window(start, end, "ts_code", STOCK_CODE);
        Map<String, Object> etfWindow = window(start, end, "ts_code", ETF_CODE);
        Map<String, Object> dateWindow = window(start, end);
        Map<String, Object> tradeDate = Map.of("trade_date", end);
        Map<String, Object> treasury = new LinkedHashMap<>(dateWindow);
        treasury.put("ts_code", "1001.CB");
        treasury.put("curve_type", "0");
        treasury.put("curve_term", "10");
        return List.of(
                spec("trade_cal", Map.of(
                                "exchange", "SSE", "start_date", start, "end_date", end),
                        "exchange,cal_date,is_open", "cal_date"),
                spec("stock_basic", Map.of("ts_code", STOCK_CODE),
                        "ts_code,list_status,list_date", "list_date"),
                spec("index_member_all", Map.of("ts_code", STOCK_CODE),
                        "l1_code,ts_code,in_date,out_date", "in_date", "out_date"),
                spec("daily", stockWindow, "ts_code,trade_date,close", "trade_date"),
                spec("adj_factor", stockWindow,
                        "ts_code,trade_date,adj_factor", "trade_date"),
                spec("index_daily", window(start, end, "ts_code", "000985.CSI"),
                        "ts_code,trade_date,close", "trade_date"),
                spec("daily_basic", stockWindow,
                        "ts_code,trade_date,pe_ttm", "trade_date"),
                spec("yc_cb", Map.copyOf(treasury),
                        "trade_date,ts_code,curve_type,curve_term,yield", "trade_date"),
                spec("index_global", window(start, end, "ts_code", "SPX"),
                        "ts_code,trade_date,close", "trade_date"),
                spec("margin", tradeDate,
                        "exchange_id,trade_date,rzye", "trade_date"),
                spec("margin_detail", Map.of("trade_date", end, "ts_code", STOCK_CODE),
                        "trade_date,ts_code,rzye", "trade_date"),
                spec("etf_basic", Map.of("ts_code", ETF_CODE),
                        "ts_code,list_date", "list_date"),
                spec("fund_basic", Map.of("ts_code", ETF_CODE, "market", "E"),
                        "ts_code,list_date,delist_date", "list_date", "delist_date"),
                spec("fund_share", etfWindow,
                        "ts_code,trade_date,fd_share", "trade_date"),
                spec("fund_nav", etfWindow,
                        "ts_code,ann_date,nav_date,unit_nav", "ann_date", "nav_date"),
                spec("fund_daily", etfWindow,
                        "ts_code,trade_date,close", "trade_date"),
                spec("forecast_vip", Map.of(
                                "period", compact(previousQuarterEnd(endDate)),
                                "ts_code", STOCK_CODE),
                        "ts_code,ann_date,end_date,type,p_change_min,p_change_max",
                        "ann_date", "end_date"),
                spec("share_float", Map.of("ts_code", STOCK_CODE),
                        "ts_code,ann_date,float_date,float_share", "ann_date", "float_date"),
                spec("stk_holdertrade", stockWindow,
                        "ts_code,ann_date,in_de,change_vol,begin_date,close_date",
                        "ann_date", "begin_date", "close_date"));
    }

    private ProbeSpec spec(
            String api,
            Map<String, Object> params,
            String fields,
            String... dateFields
    ) {
        return new ProbeSpec(api, params, fields, List.of(dateFields));
    }

    private Map<String, Object> window(String start, String end, String... extra) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("start_date", start);
        params.put("end_date", end);
        for (int index = 0; index < extra.length; index += 2) {
            params.put(extra[index], extra[index + 1]);
        }
        return Map.copyOf(params);
    }

    private String compact(LocalDate date) {
        return date.format(COMPACT_DATE);
    }

    private LocalDate previousQuarterEnd(LocalDate date) {
        int currentQuarter = (date.getMonthValue() - 1) / 3;
        if (currentQuarter == 0) {
            return LocalDate.of(date.getYear() - 1, Month.DECEMBER, 31);
        }
        Month month = Month.of(currentQuarter * 3);
        return LocalDate.of(date.getYear(), month, month.length(date.isLeapYear()));
    }

    private record ProbeSpec(
            String api,
            Map<String, Object> params,
            String fields,
            List<String> dateFields
    ) {
    }

    private static final class ProbeMappingException extends RuntimeException {
    }
}
