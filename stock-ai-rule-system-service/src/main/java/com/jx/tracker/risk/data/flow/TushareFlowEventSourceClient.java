package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TuShare structured flow/event adapter. Announcements deliberately remain on
 * the CNInfo/AKTools route and are never requested from TuShare.
 */
public final class TushareFlowEventSourceClient implements FlowEventSourceClient {

    public static final String SOURCE = "tushare";
    private static final DateTimeFormatter COMPACT_DATE =
            DateTimeFormatter.BASIC_ISO_DATE;
    private static final RiskObjectKey CN_A =
            new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final int FUND_BASIC_ROW_LIMIT = 15_000;
    private static final int FUND_SHARE_ROW_LIMIT = 2_000;
    private static final int FUND_NAV_ROW_LIMIT = 2_000;
    private static final int FUND_DAILY_ROW_LIMIT = 5_000;
    private static final int FORECAST_VIP_ROW_LIMIT = 2_000;
    private static final int HOLDER_TRADE_ROW_LIMIT = 3_000;
    private static final int MARGIN_ROW_LIMIT = 4_000;
    private static final int MARKET_DETAIL_ROW_LIMIT = 6_000;
    private static final BigDecimal SHARE_UNIT_MULTIPLIER =
            new BigDecimal("10000");
    private static final LocalDate BSE_LAUNCH_DATE =
            LocalDate.of(2021, 11, 15);
    private static final List<String> MARGIN_METRICS =
            List.of("rzye", "rqye", "rzmre", "rzche", "rzrqye");

    private final TushareRiskHttpClient httpClient;
    private final Clock clock;

    public TushareFlowEventSourceClient(
            TushareRiskHttpClient httpClient,
            Clock clock
    ) {
        if (httpClient == null || clock == null) {
            throw new IllegalArgumentException(
                    "TuShare HTTP client and clock are required");
        }
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Override
    public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        LocalDateTime fetchedAt = LocalDateTime.now(clock);
        try {
            request.dataset().validateObjects(request.objects());
            return switch (request.dataset()) {
                case MARGIN_FINANCING -> margin(
                        request, fetchedAt, tradingCalendar(request));
                case ETF_FUND_FLOW -> etfFlow(
                        request, fetchedAt, tradingCalendar(request));
                case EARNINGS_FORECAST -> forecasts(
                        request, fetchedAt, tradingCalendar(request));
                case SHARE_UNLOCK -> unlocks(
                        request, fetchedAt, tradingCalendar(request));
                case SHARE_REDUCTION -> reductions(
                        request, fetchedAt, tradingCalendar(request));
                case STOCK_ANNOUNCEMENT -> FlowEventSourceBatch.unavailable(
                        SOURCE,
                        "stock_announcement is routed directly to CNInfo/AKTools",
                        fetchedAt);
            };
        } catch (TushareRiskException exception) {
            if (exception.category()
                    == TushareRiskException.Category.CONFIGURATION) {
                throw exception;
            }
            String apiName = exception.apiName() == null
                    ? request.dataset().code() : exception.apiName();
            return FlowEventSourceBatch.unavailable(
                    SOURCE,
                    apiName + " "
                            + exception.category().name()
                                    .toLowerCase(Locale.ROOT)
                            + " failure",
                    fetchedAt);
        } catch (IllegalArgumentException exception) {
            return FlowEventSourceBatch.unavailable(
                    SOURCE,
                    "dataset=" + request.dataset().code()
                            + " api=" + datasetApis(request.dataset())
                            + " mapping failed",
                    fetchedAt);
        } catch (RuntimeException exception) {
            return FlowEventSourceBatch.unavailable(
                    SOURCE,
                    "dataset=" + request.dataset().code()
                            + " api=" + datasetApis(request.dataset())
                            + " runtime failure",
                    fetchedAt);
        }
    }

    private FlowEventSourceBatch margin(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt,
            TradingCalendar calendar
    ) {
        List<String> gaps = new ArrayList<>();
        LocalDate warmupDate =
                calendar.previousOpenBefore(request.startDate());
        if (warmupDate == null) {
            gaps.add("trade_cal has no previous open day before "
                    + request.startDate());
            warmupDate = request.startDate();
        }
        TushareRiskResponse aggregateResponse = query(
                "margin", dateWindow(warmupDate, request.endDate()),
                "exchange_id,trade_date,rzye,rqye,rzmre,rzche,rzrqye");
        Map<LocalDate, MarginAggregate> byDate = new LinkedHashMap<>();
        for (Map<String, Object> row : aggregateResponse.rows()) {
            String exchange = text(row, "exchange_id");
            if (!List.of("SSE", "SZSE", "BSE").contains(exchange)) {
                continue;
            }
            LocalDate tradeDate = date(row, "trade_date");
            if (tradeDate.isBefore(warmupDate)
                    || tradeDate.isAfter(request.endDate())) {
                continue;
            }
            if (!calendar.isOpen(tradeDate)) {
                gaps.add("margin returned closed trade date "
                        + compact(tradeDate));
                continue;
            }
            byDate.computeIfAbsent(tradeDate, ignored -> new MarginAggregate())
                    .add(exchange, row);
        }
        Map<LocalDate, MarginDetailAggregate> details = new LinkedHashMap<>();
        List<LocalDate> detailDates =
                calendar.openDays(warmupDate, request.endDate());
        for (int index = 0; index < detailDates.size(); index += 2) {
            LocalDate sliceStart = detailDates.get(index);
            LocalDate sliceEnd = detailDates.get(
                    Math.min(index + 1, detailDates.size() - 1));
            TushareRiskResponse detailResponse = query(
                    "margin_detail",
                    dateWindow(sliceStart, sliceEnd),
                    "trade_date,ts_code,rzye,rqye,rzmre,rzche,rzrqye");
            if (detailResponse.rows().size()
                    >= MARKET_DETAIL_ROW_LIMIT) {
                gaps.add("margin_detail "
                        + compact(sliceStart) + "-"
                        + compact(sliceEnd)
                        + " reached documented "
                        + MARKET_DETAIL_ROW_LIMIT
                        + "-row boundary");
            }
            for (Map<String, Object> row : detailResponse.rows()) {
                String code = normalizedCode(row, "ts_code");
                LocalDate tradeDate = date(row, "trade_date");
                if (tradeDate.isBefore(sliceStart)
                        || tradeDate.isAfter(sliceEnd)
                        || !calendar.isOpen(tradeDate)) {
                    continue;
                }
                details.computeIfAbsent(
                        tradeDate,
                        ignored -> new MarginDetailAggregate())
                        .add(exchangeForStockCode(code), row);
            }
        }
        for (LocalDate openDate : detailDates) {
            MarginAggregate aggregate = byDate.get(openDate);
            if (aggregate == null) {
                gaps.add("margin missing open day "
                        + compact(openDate));
                continue;
            }
            List<String> exchanges = new ArrayList<>(
                    List.of("SSE", "SZSE"));
            if (!openDate.isBefore(BSE_LAUNCH_DATE)) {
                exchanges.add("BSE");
            }
            MarginDetailAggregate detail = details.get(openDate);
            for (String exchange : exchanges) {
                if (!aggregate.hasExchange(exchange)) {
                    gaps.add("margin " + compact(openDate)
                            + " missing exchange " + exchange);
                    continue;
                }
                if (detail == null
                        || detail.count(exchange) == 0) {
                    gaps.add("margin_detail "
                            + compact(openDate)
                            + " missing exchange " + exchange);
                    continue;
                }
                for (String metric : MARGIN_METRICS) {
                    if (detail.metric(exchange, metric)
                            .compareTo(aggregate.metric(
                                    exchange, metric)) != 0) {
                        gaps.add("margin_detail "
                                + compact(openDate) + " "
                                + exchange + " " + metric
                                + " does not match margin aggregate");
                    }
                }
            }
        }
        List<FlowEventSourceRecord> records = new ArrayList<>();
        BigDecimal previousBalance = null;
        for (Map.Entry<LocalDate, MarginAggregate> entry :
                byDate.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey()).toList()) {
            LocalDate tradeDate = entry.getKey();
            MarginAggregate aggregate = entry.getValue();
            if (tradeDate.isBefore(request.startDate())) {
                previousBalance = aggregate.financingBalance;
                continue;
            }
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (previousBalance != null) {
                attributes.put("previousBalance", previousBalance);
                attributes.put("referenceBalance", previousBalance);
            }
            attributes.put("financingBuy", aggregate.financingBuy);
            attributes.put("financingRepay", aggregate.financingRepay);
            attributes.put("securitiesLendingBalance",
                    aggregate.securitiesLendingBalance);
            attributes.put("combinedBalance", aggregate.combinedBalance);
            attributes.put("detailRowCount",
                    details.getOrDefault(
                            tradeDate,
                            new MarginDetailAggregate()).count());
            attributes.put("formulaVersion", "margin-market-sum-rzye-v1");
            LocalDateTime observedAt = tradeDate.atTime(15, 0);
            LocalDateTime availableAt =
                    calendar.requiredNextOpenAfter(tradeDate)
                            .atTime(8, 30);
            records.add(record(
                    "margin_financing:CN-A:" + tradeDate,
                    CN_A, tradeDate, observedAt, observedAt, availableAt,
                    aggregate.financingBalance, "currency",
                    "balance", "融资余额", attributes));
            previousBalance = aggregate.financingBalance;
        }
        boolean aggregateTruncated =
                aggregateResponse.rows().size() >= MARGIN_ROW_LIMIT;
        if (aggregateTruncated) {
            gaps.add("margin reached documented "
                    + MARGIN_ROW_LIMIT + "-row boundary");
        }
        if (records.isEmpty()) {
            return FlowEventSourceBatch.insufficientHistory(
                    SOURCE,
                    gaps.isEmpty()
                            ? "margin returned no matching rows"
                            : String.join("; ", gaps),
                    earliest(byDate.keySet()), fetchedAt);
        }
        if (!gaps.isEmpty()) {
            return partial(
                    records, String.join("; ", gaps),
                    earliest(byDate.keySet()), fetchedAt);
        }
        return available(records, earliest(byDate.keySet()), fetchedAt);
    }

    private TradingCalendar tradingCalendar(
            FlowEventSourceRequest request
    ) {
        LocalDate start = request.startDate().minusDays(14);
        LocalDate end = request.endDate().plusDays(14);
        TushareRiskResponse response = query(
                "trade_cal",
                Map.of(
                        "exchange", "SSE",
                        "start_date", compact(start),
                        "end_date", compact(end)),
                "exchange,cal_date,is_open,pretrade_date");
        List<LocalDate> openDays = response.rows().stream()
                .filter(row -> "1".equals(
                        optionalText(row, "is_open")))
                .map(row -> date(row, "cal_date"))
                .distinct().sorted().toList();
        return new TradingCalendar(openDays);
    }

    private String exchangeForStockCode(String code) {
        if (code.endsWith(".SH")) {
            return "SSE";
        }
        if (code.endsWith(".SZ")) {
            return "SZSE";
        }
        if (code.endsWith(".BJ")) {
            return "BSE";
        }
        throw new IllegalArgumentException(
                "unsupported exchange suffix in ts_code");
    }

    private FlowEventSourceBatch etfFlow(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt,
            TradingCalendar calendar
    ) {
        LocalDate historyStart = request.startDate().minusYears(1);
        TushareRiskResponse basicResponse = query(
                "fund_basic", Map.of("market", "E"),
                "ts_code,name,fund_type,type,status,list_date,"
                        + "delist_date,market");
        List<String> fundCodes = basicResponse.rows().stream()
                .filter(row -> confirmedEtf(
                        row, historyStart, request.endDate()))
                .map(row -> normalizedCode(row, "ts_code"))
                .distinct().sorted().toList();
        Map<String, List<SharePoint>> sharesByFund =
                new LinkedHashMap<>();
        Map<LocalDate, EtfAggregate> byDate = new LinkedHashMap<>();
        List<String> gaps = new ArrayList<>();
        if (basicResponse.rows().size() >= FUND_BASIC_ROW_LIMIT) {
            gaps.add("fund_basic reached documented "
                    + FUND_BASIC_ROW_LIMIT + "-row boundary");
        }
        if (fundCodes.isEmpty()) {
            gaps.add("fund_basic returned no confirmed ETF universe");
        }
        int coveredFundCount = 0;
        for (String fundCode : fundCodes) {
            Map<String, Object> slice = new LinkedHashMap<>(
                    dateWindow(historyStart, request.endDate()));
            slice.put("ts_code", fundCode);
            TushareRiskResponse shareResponse = query(
                    "fund_share", slice,
                    "ts_code,trade_date,fd_share");
            TushareRiskResponse navResponse = query(
                    "fund_nav", slice,
                    "ts_code,ann_date,nav_date,unit_nav");
            TushareRiskResponse dailyResponse = query(
                    "fund_daily", slice,
                    "ts_code,trade_date,close");
            if (shareResponse.rows().size()
                    >= FUND_SHARE_ROW_LIMIT) {
                gaps.add("fund_share " + fundCode
                        + " reached documented "
                        + FUND_SHARE_ROW_LIMIT
                        + "-row boundary");
            }
            if (navResponse.rows().size() >= FUND_NAV_ROW_LIMIT) {
                gaps.add("fund_nav " + fundCode
                        + " reached documented "
                        + FUND_NAV_ROW_LIMIT + "-row boundary");
            }
            if (dailyResponse.rows().size()
                    >= FUND_DAILY_ROW_LIMIT) {
                gaps.add("fund_daily " + fundCode
                        + " reached documented "
                        + FUND_DAILY_ROW_LIMIT
                        + "-row boundary");
            }
            Map<LocalDate, NavPoint> navByDate =
                    new LinkedHashMap<>();
            for (Map<String, Object> row : navResponse.rows()) {
                if (!fundCode.equals(
                        normalizedCode(row, "ts_code"))) {
                    continue;
                }
                LocalDate navDate = date(row, "nav_date");
                NavPoint candidate = new NavPoint(
                        decimal(row, "unit_nav"),
                        date(row, "ann_date"));
                NavPoint existing = navByDate.get(navDate);
                if (existing == null
                        || candidate.announcementDate().isBefore(
                                existing.announcementDate())) {
                    navByDate.put(navDate, candidate);
                } else if (candidate.announcementDate().equals(
                        existing.announcementDate())
                        && candidate.unitNav().compareTo(
                                existing.unitNav()) != 0) {
                    gaps.add("conflicting fund_nav " + fundCode
                            + " " + navDate + " announced "
                            + candidate.announcementDate());
                }
            }
            Map<LocalDate, BigDecimal> closeByDate =
                    new LinkedHashMap<>();
            for (Map<String, Object> row : dailyResponse.rows()) {
                if (!fundCode.equals(
                        normalizedCode(row, "ts_code"))) {
                    continue;
                }
                LocalDate tradeDate = date(row, "trade_date");
                BigDecimal close = decimal(row, "close");
                BigDecimal existing = closeByDate.putIfAbsent(
                        tradeDate, close);
                if (existing != null
                        && existing.compareTo(close) != 0) {
                    gaps.add("conflicting fund_daily "
                            + fundCode + " " + tradeDate);
                }
            }
            List<SharePoint> rawShares = new ArrayList<>();
            for (Map<String, Object> row : shareResponse.rows()) {
                if (!fundCode.equals(
                        normalizedCode(row, "ts_code"))) {
                    continue;
                }
                rawShares.add(new SharePoint(
                        date(row, "trade_date"),
                        decimal(row, "fd_share")));
            }
            sharesByFund.put(fundCode, rawShares);
            List<SharePoint> shares = rawShares.stream()
                    .sorted(Comparator.comparing(SharePoint::tradeDate))
                    .toList();
            if (shares.size() < 2) {
                gaps.add("fund " + fundCode
                        + " share continuity requires at least "
                        + "two observations");
                continue;
            }
            SharePoint previous = null;
            boolean calculated = false;
            for (SharePoint current : shares) {
                if (previous != null
                        && inTradeWindow(request, current.tradeDate())) {
                    NavPoint nav =
                            navByDate.get(current.tradeDate());
                    BigDecimal close =
                            closeByDate.get(current.tradeDate());
                    if (nav == null || close == null) {
                        gaps.add("fund " + fundCode + " "
                                + current.tradeDate()
                                + " missing fund_nav/fund_daily");
                    } else {
                        BigDecimal netFlow = current.share()
                                .subtract(previous.share())
                                .multiply(SHARE_UNIT_MULTIPLIER)
                                .multiply(nav.unitNav())
                                .stripTrailingZeros();
                        BigDecimal referenceAssets = current.share()
                                .multiply(SHARE_UNIT_MULTIPLIER)
                                .multiply(nav.unitNav())
                                .stripTrailingZeros();
                        byDate.computeIfAbsent(
                                current.tradeDate(),
                                ignored -> new EtfAggregate())
                                .add(
                                        fundCode, netFlow,
                                        referenceAssets, close,
                                        nav.unitNav(),
                                        nav.announcementDate(),
                                        later(
                                                calendar
                                                        .requiredNextOpenAfter(
                                                                current.tradeDate())
                                                        .atTime(8, 30),
                                                calendar
                                                        .requiredNextOpenAfter(
                                                                nav.announcementDate())
                                                        .atTime(8, 30)));
                        calculated = true;
                    }
                }
                previous = current;
            }
            if (calculated) {
                coveredFundCount++;
            } else {
                gaps.add("fund " + fundCode
                        + " has no calculable continuity in "
                        + "requested window");
            }
        }
        int confirmedFundCount = fundCodes.size();
        int finalCoveredFundCount = coveredFundCount;
        List<FlowEventSourceRecord> records = new ArrayList<>();
        byDate.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    LocalDate tradeDate = entry.getKey();
                    EtfAggregate aggregate = entry.getValue();
                    Map<String, Object> attributes = Map.ofEntries(
                            Map.entry(
                                    "referenceAssets",
                                    aggregate.referenceAssets),
                            Map.entry(
                                    "shareUnitMultiplier",
                                    SHARE_UNIT_MULTIPLIER),
                            Map.entry(
                                    "fundDailyClose",
                                    aggregate.weightedClose()),
                            Map.entry(
                                    "fundDailyCloseWeightedAverage",
                                    aggregate.weightedClose()),
                            Map.entry(
                                    "fundCount", aggregate.fundCount),
                            Map.entry(
                                    "fundContributions",
                                    aggregate.contributions()),
                            Map.entry(
                                    "universeFundCount",
                                    confirmedFundCount),
                            Map.entry(
                                    "coveredFundCount",
                                    finalCoveredFundCount),
                            Map.entry(
                                    "formula",
                                    "(fd_share[t]-fd_share[t-1])"
                                            + "*10000*unit_nav[t]"),
                            Map.entry(
                                    "formulaVersion",
                                    "fund-share-delta-times-unit-nav-v1"),
                            Map.entry(
                                    "secondaryMarketAmountUsed", false));
                    records.add(record(
                            "etf_fund_flow:CN-A:" + tradeDate,
                            CN_A, tradeDate, tradeDate.atTime(15, 0),
                            tradeDate.atTime(15, 0),
                            aggregate.availableAt,
                            aggregate.netFlow, "currency",
                            "etf_redemption_flow",
                            "ETF 份额变化衍生净申赎", attributes));
                });
        if (records.isEmpty()) {
            String reason = gaps.isEmpty()
                            ? "ETF share history has no calculable share delta"
                            : String.join("; ", gaps);
            return FlowEventSourceBatch.insufficientHistory(
                    SOURCE, reason,
                    earliestShareDate(sharesByFund), fetchedAt);
        }
        if (!gaps.isEmpty()
                || coveredFundCount != confirmedFundCount) {
            return partial(
                    records, String.join("; ", gaps),
                    earliestShareDate(sharesByFund), fetchedAt);
        }
        return available(
                records, earliestShareDate(sharesByFund), fetchedAt);
    }

    private boolean confirmedEtf(
            Map<String, Object> row,
            LocalDate historyStart,
            LocalDate endDate
    ) {
        String name = defaultText(row, "name", "");
        String type = defaultText(row, "type", "");
        if (!name.toUpperCase(Locale.ROOT).contains("ETF")
                && !type.toUpperCase(Locale.ROOT).contains("ETF")) {
            return false;
        }
        LocalDate listDate = optionalDate(row, "list_date");
        LocalDate delistDate = optionalDate(row, "delist_date");
        return (listDate == null || !listDate.isAfter(endDate))
                && (delistDate == null
                        || !delistDate.isBefore(historyStart));
    }

    private FlowEventSourceBatch forecasts(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt,
            TradingCalendar calendar
    ) {
        List<FlowEventSourceRecord> records = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        LocalDate earliest = null;
        Map<String, RiskObjectKey> requestedStocks =
                request.objects().stream()
                        .filter(object -> object.objectType()
                                == RiskObjectType.STOCK)
                        .collect(java.util.stream.Collectors.toMap(
                                RiskObjectKey::objectId,
                                object -> object,
                                (left, right) -> left,
                                LinkedHashMap::new));
        for (LocalDate period : forecastPeriods(
                request.startDate(), request.endDate())) {
            TushareRiskResponse response = query(
                    "forecast_vip",
                    Map.of("period", compact(period)),
                    "ts_code,ann_date,end_date,type,p_change_min,"
                            + "p_change_max,net_profit_min,"
                            + "net_profit_max,last_parent_net,"
                            + "first_ann_date,summary,change_reason");
            if (response.rows().size()
                    >= FORECAST_VIP_ROW_LIMIT) {
                gaps.add("forecast_vip period=" + compact(period)
                        + " reached "
                        + FORECAST_VIP_ROW_LIMIT
                        + "-row boundary");
            }
            for (Map<String, Object> row : response.rows()) {
                String code = normalizedCode(row, "ts_code");
                RiskObjectKey object = requestedStocks.get(code);
                if (object == null) {
                    continue;
                }
                LocalDate announcementDate = date(row, "ann_date");
                LocalDateTime availableAt =
                        calendar.requiredNextOpenAfter(
                                announcementDate).atStartOfDay();
                if (!inAvailabilityWindow(request, availableAt)) {
                    continue;
                }
                earliest = earlier(earliest, announcementDate);
                BigDecimal minimum =
                        optionalDecimal(row, "p_change_min");
                BigDecimal maximum =
                        optionalDecimal(row, "p_change_max");
                if (minimum == null && maximum == null) {
                    gaps.add("forecast " + object.objectId()
                            + " missing p_change range");
                    continue;
                }
                BigDecimal value = midpoint(minimum, maximum);
                String type = text(row, "type");
                LocalDate reportPeriod = date(row, "end_date");
                if (!reportPeriod.equals(period)) {
                    gaps.add("forecast_vip " + object.objectId()
                            + " returned mismatched report period "
                            + reportPeriod + " for slice "
                            + period);
                    continue;
                }
                Map<String, Object> attributes =
                        new LinkedHashMap<>();
                attributes.put("sourceApi", "forecast_vip");
                attributes.put("economicMeaning", "cash_flow");
                attributes.put("announcementCategory",
                        "earnings_warning");
                attributes.put("forecastType", type);
                putIfNotNull(attributes, "pChangeMin", minimum);
                putIfNotNull(attributes, "pChangeMax", maximum);
                putIfNotNull(
                        attributes, "netProfitMin",
                        optionalDecimal(row, "net_profit_min"));
                putIfNotNull(
                        attributes, "netProfitMax",
                        optionalDecimal(row, "net_profit_max"));
                putIfNotNull(
                        attributes, "last_parent_net",
                        optionalDecimal(row, "last_parent_net"));
                putIfNotNull(
                        attributes, "summary",
                        optionalText(row, "summary"));
                putIfNotNull(
                        attributes, "changeReason",
                        optionalText(row, "change_reason"));
                attributes.put("reportPeriod", reportPeriod);
                attributes.put(
                        "formulaVersion",
                        "forecast-range-midpoint-v1");
                attributes.put(
                        "adverse",
                        value.signum() < 0
                                || containsAny(
                                        type,
                                        "预减", "首亏", "续亏", "略减"));
                String id = "earnings_forecast:"
                        + object.objectId() + ":"
                        + announcementDate + ":" + reportPeriod
                        + ":" + type;
                records.add(record(
                        id, object, announcementDate,
                        announcementDate.atStartOfDay(),
                        announcementDate.atStartOfDay(),
                        availableAt, value, "percent",
                        "forecast_change", type, attributes));
            }
        }
        return eventBatch(
                request, records, gaps, earliest, fetchedAt);
    }

    private FlowEventSourceBatch unlocks(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt,
            TradingCalendar calendar
    ) {
        List<FlowEventSourceRecord> records = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        LocalDate earliest = null;
        for (RiskObjectKey object : sortedStocks(request)) {
            TushareRiskResponse response = query(
                    "share_float",
                    Map.of("ts_code", object.objectId()),
                    "ts_code,ann_date,float_date,float_share,"
                            + "float_ratio,holder_name,share_type");
            if (response.rows().size() >= MARKET_DETAIL_ROW_LIMIT) {
                gaps.add("share_float " + object.objectId()
                        + " reached documented "
                        + MARKET_DETAIL_ROW_LIMIT + "-row boundary");
            }
            for (Map<String, Object> row : response.rows()) {
                if (!object.objectId().equals(
                        normalizedCode(row, "ts_code"))) {
                    continue;
                }
                LocalDate announcementDate = date(row, "ann_date");
                LocalDateTime availableAt =
                        calendar.requiredNextOpenAfter(
                                announcementDate).atStartOfDay();
                if (!inAvailabilityWindow(request, availableAt)) {
                    continue;
                }
                earliest = earlier(earliest, announcementDate);
                LocalDate floatDate = date(row, "float_date");
                BigDecimal shares = decimal(row, "float_share");
                BigDecimal ratio =
                        optionalDecimal(row, "float_ratio");
                String holder =
                        defaultText(row, "holder_name", "未披露股东");
                String shareType =
                        defaultText(row, "share_type", "未披露类型");
                Map<String, Object> attributes =
                        new LinkedHashMap<>();
                attributes.put("holderName", holder);
                attributes.put("shareType", shareType);
                attributes.put("scheduled",
                        floatDate.isAfter(announcementDate));
                attributes.put("adverse", true);
                putIfNotNull(
                        attributes, "modifierRatio",
                        ratio == null ? null : ratio.abs());
                attributes.put("formulaVersion",
                        "share-float-factual-v1");
                records.add(record(
                        "share_unlock:" + object.objectId()
                                + ":" + floatDate + ":"
                                + announcementDate + ":" + holder
                                + ":" + shareType,
                        object, floatDate,
                        floatDate.atTime(9, 30),
                        announcementDate.atStartOfDay(),
                        availableAt, shares, "shares",
                        "share_unlock", "限售股解禁",
                        attributes));
            }
        }
        return eventBatch(
                request, records, gaps, earliest, fetchedAt);
    }

    private FlowEventSourceBatch reductions(
            FlowEventSourceRequest request,
            LocalDateTime fetchedAt,
            TradingCalendar calendar
    ) {
        List<FlowEventSourceRecord> records = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        LocalDate earliest = null;
        for (RiskObjectKey object : sortedStocks(request)) {
            Map<String, Object> params =
                    new LinkedHashMap<>(dateWindow(request));
            params.put("ts_code", object.objectId());
            params.put("trade_type", "DE");
            TushareRiskResponse response = query(
                    "stk_holdertrade", params,
                    "ts_code,ann_date,holder_name,holder_type,in_de,"
                            + "change_vol,change_ratio,after_share,"
                            + "after_ratio,avg_price,total_share,"
                            + "begin_date,close_date");
            if (response.rows().size()
                    >= HOLDER_TRADE_ROW_LIMIT) {
                gaps.add("stk_holdertrade " + object.objectId()
                        + " reached documented "
                        + HOLDER_TRADE_ROW_LIMIT + "-row boundary");
            }
            for (Map<String, Object> row : response.rows()) {
                if (!object.objectId().equals(
                        normalizedCode(row, "ts_code"))
                        || !"DE".equals(
                                optionalText(row, "in_de"))) {
                    continue;
                }
                LocalDate announcementDate = date(row, "ann_date");
                LocalDateTime availableAt =
                        calendar.requiredNextOpenAfter(
                                announcementDate).atStartOfDay();
                if (!inAvailabilityWindow(request, availableAt)) {
                    continue;
                }
                earliest = earlier(earliest, announcementDate);
                LocalDate beginDate =
                        optionalDate(row, "begin_date");
                LocalDate closeDate =
                        optionalDate(row, "close_date");
                LocalDate occurredDate = closeDate == null
                        ? beginDate == null
                                ? announcementDate : beginDate
                        : closeDate;
                BigDecimal volume =
                        decimal(row, "change_vol").abs();
                BigDecimal ratio =
                        optionalDecimal(row, "change_ratio");
                String holder =
                        defaultText(row, "holder_name", "未披露股东");
                Map<String, Object> attributes =
                        new LinkedHashMap<>();
                attributes.put("actualReduction", true);
                attributes.put("adverse", true);
                attributes.put("direction", "DE");
                attributes.put("shareholder", holder);
                putIfNotNull(
                        attributes, "holderType",
                        optionalText(row, "holder_type"));
                putIfNotNull(
                        attributes, "changeStartDate", beginDate);
                putIfNotNull(
                        attributes, "modifierRatio",
                        ratio == null ? null : ratio.abs());
                putIfNotNull(
                        attributes, "averagePrice",
                        optionalDecimal(row, "avg_price"));
                attributes.put("formulaVersion",
                        "stk-holdertrade-de-factual-v1");
                records.add(record(
                        "share_reduction:"
                                + object.objectId() + ":" + holder
                                + ":" + announcementDate + ":"
                                + occurredDate + ":" + volume,
                        object, occurredDate,
                        occurredDate.atTime(15, 0),
                        announcementDate.atStartOfDay(),
                        availableAt, volume, "shares",
                        "share_reduction", "股东减持",
                        attributes));
            }
        }
        return eventBatch(
                request, records, gaps, earliest, fetchedAt);
    }

    private FlowEventSourceBatch eventBatch(
            FlowEventSourceRequest request,
            List<FlowEventSourceRecord> records,
            List<String> gaps,
            LocalDate earliest,
            LocalDateTime fetchedAt
    ) {
        if (records.isEmpty()) {
            if (!gaps.isEmpty()) {
                return FlowEventSourceBatch.insufficientHistory(
                        SOURCE, String.join("; ", gaps),
                        earliest, fetchedAt);
            }
            return new FlowEventSourceBatch(
                    SOURCE, List.of(),
                    RiskDataQualityStatus.VALID_ZERO, null,
                    boundaryCursor(request), earliest, true,
                    fetchedAt, null);
        }
        List<FlowEventSourceRecord> unique = deduplicate(records);
        if (!gaps.isEmpty()) {
            return partial(
                    unique, String.join("; ", gaps),
                    earliest, fetchedAt);
        }
        return available(unique, earliest, fetchedAt);
    }

    private FlowEventSourceBatch available(
            List<FlowEventSourceRecord> records,
            LocalDate earliest,
            LocalDateTime fetchedAt
    ) {
        return new FlowEventSourceBatch(
                SOURCE, records,
                RiskDataQualityStatus.AVAILABLE, null,
                maxCursor(records), earliest, true,
                fetchedAt, null);
    }

    private FlowEventSourceBatch partial(
            List<FlowEventSourceRecord> records,
            String reason,
            LocalDate earliest,
            LocalDateTime fetchedAt
    ) {
        return new FlowEventSourceBatch(
                SOURCE, records,
                RiskDataQualityStatus.AVAILABLE, reason,
                null, earliest, false, fetchedAt, null);
    }

    private TushareRiskResponse query(
            String apiName,
            Map<String, Object> params,
            String fields
    ) {
        return httpClient.query(
                new TushareRiskRequest(
                        apiName, Map.copyOf(params), fields));
    }

    private Map<String, Object> dateWindow(
            FlowEventSourceRequest request
    ) {
        return dateWindow(request.startDate(), request.endDate());
    }

    private Map<String, Object> dateWindow(
            LocalDate startDate,
            LocalDate endDate
    ) {
        return Map.of(
                "start_date", compact(startDate),
                "end_date", compact(endDate));
    }

    private List<RiskObjectKey> sortedStocks(
            FlowEventSourceRequest request
    ) {
        return request.objects().stream()
                .filter(object -> object.objectType()
                        == RiskObjectType.STOCK)
                .sorted(Comparator.comparing(RiskObjectKey::objectId))
                .toList();
    }

    private FlowEventSourceRecord record(
            String id,
            RiskObjectKey object,
            LocalDate tradeDate,
            LocalDateTime occurredAt,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            BigDecimal value,
            String unit,
            String eventCode,
            String title,
            Map<String, Object> attributes
    ) {
        return new FlowEventSourceRecord(
                id, availableAt + "|" + id, object,
                tradeDate, occurredAt, observedAt, availableAt,
                value, unit, eventCode, title, attributes);
    }

    private List<FlowEventSourceRecord> deduplicate(
            List<FlowEventSourceRecord> records
    ) {
        Map<String, FlowEventSourceRecord> unique =
                new LinkedHashMap<>();
        records.forEach(record -> unique.putIfAbsent(
                record.object().objectId() + ":"
                        + record.recordId() + ":"
                        + record.availableAt(),
                record));
        return new ArrayList<>(unique.values());
    }

    private String maxCursor(List<FlowEventSourceRecord> records) {
        return records.stream()
                .map(FlowEventSourceRecord::cursor)
                .max(String::compareTo).orElseThrow();
    }

    private String boundaryCursor(FlowEventSourceRequest request) {
        return request.dataset().code() + ":" + request.endDate();
    }

    private String datasetApis(FlowEventDataset dataset) {
        return switch (dataset) {
            case MARGIN_FINANCING -> "margin,margin_detail";
            case ETF_FUND_FLOW ->
                    "fund_share,fund_nav,fund_daily";
            case EARNINGS_FORECAST -> "forecast_vip";
            case STOCK_ANNOUNCEMENT -> "cninfo/aktools";
            case SHARE_UNLOCK -> "share_float";
            case SHARE_REDUCTION -> "stk_holdertrade";
        };
    }

    private boolean inTradeWindow(
            FlowEventSourceRequest request,
            LocalDate tradeDate
    ) {
        return !tradeDate.isBefore(request.startDate())
                && !tradeDate.isAfter(request.endDate());
    }

    private boolean inAvailabilityWindow(
            FlowEventSourceRequest request,
            LocalDateTime availableAt
    ) {
        LocalDate date = availableAt.toLocalDate();
        return !date.isBefore(request.startDate())
                && !date.isAfter(request.endDate());
    }

    private String normalizedCode(
            Map<String, Object> row,
            String key
    ) {
        return stock(text(row, key)).objectId();
    }

    private RiskObjectKey stock(String rawCode) {
        String normalized =
                rawCode == null ? "" : rawCode.trim().toUpperCase(
                        Locale.ROOT);
        if (!normalized.matches("\\d{6}\\.(SH|SZ|BJ)")) {
            throw new IllegalArgumentException(
                    "invalid TuShare stock/fund code field ts_code");
        }
        return new RiskObjectKey(
                RiskObjectType.STOCK, normalized);
    }

    private String compact(LocalDate date) {
        return date.format(COMPACT_DATE);
    }

    private List<LocalDate> forecastPeriods(
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate first = quarterEnd(startDate.minusMonths(3));
        LocalDate last = LocalDate.of(
                endDate.getYear(), 12, 31);
        List<LocalDate> periods = new ArrayList<>();
        for (LocalDate period = first;
             !period.isAfter(last);
             period = period.plusMonths(3)
                     .with(java.time.temporal.TemporalAdjusters
                             .lastDayOfMonth())) {
            periods.add(period);
        }
        return List.copyOf(periods);
    }

    private LocalDate quarterEnd(LocalDate date) {
        int quarterEndMonth =
                ((date.getMonthValue() - 1) / 3 + 1) * 3;
        return LocalDate.of(
                date.getYear(), quarterEndMonth, 1)
                .with(java.time.temporal.TemporalAdjusters
                        .lastDayOfMonth());
    }

    private LocalDate date(
            Map<String, Object> row,
            String key
    ) {
        String value = text(row, key);
        try {
            return value.matches("^\\d{8}$")
                    ? LocalDate.parse(value, COMPACT_DATE)
                    : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "invalid TuShare date field " + key);
        }
    }

    private LocalDate optionalDate(
            Map<String, Object> row,
            String key
    ) {
        String value = optionalText(row, key);
        if (value == null) {
            return null;
        }
        try {
            return value.matches("^\\d{8}$")
                    ? LocalDate.parse(value, COMPACT_DATE)
                    : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "invalid TuShare date field " + key);
        }
    }

    private BigDecimal decimal(
            Map<String, Object> row,
            String key
    ) {
        BigDecimal value = optionalDecimal(row, key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing TuShare field " + key);
        }
        return value;
    }

    private BigDecimal optionalDecimal(
            Map<String, Object> row,
            String key
    ) {
        Object raw = row.get(key);
        if (raw == null || raw.toString().isBlank()) {
            return null;
        }
        try {
            return raw instanceof BigDecimal decimal
                    ? decimal
                    : new BigDecimal(
                            raw.toString().replace(",", ""));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "invalid TuShare decimal field " + key);
        }
    }

    private String text(
            Map<String, Object> row,
            String key
    ) {
        String value = optionalText(row, key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "missing TuShare field " + key);
        }
        return value;
    }

    private String optionalText(
            Map<String, Object> row,
            String key
    ) {
        Object value = row.get(key);
        return value == null || value.toString().isBlank()
                ? null : value.toString().trim();
    }

    private String defaultText(
            Map<String, Object> row,
            String key,
            String fallback
    ) {
        String value = optionalText(row, key);
        return value == null ? fallback : value;
    }

    private BigDecimal midpoint(
            BigDecimal minimum,
            BigDecimal maximum
    ) {
        if (minimum == null) {
            return maximum;
        }
        if (maximum == null) {
            return minimum;
        }
        return minimum.add(maximum)
                .divide(BigDecimal.valueOf(2), 8,
                        RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void putIfNotNull(
            Map<String, Object> target,
            String key,
            Object value
    ) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private LocalDate earlier(
            LocalDate left,
            LocalDate right
    ) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private LocalDate earliest(
            java.util.Collection<LocalDate> dates
    ) {
        return dates.stream().min(LocalDate::compareTo)
                .orElse(null);
    }

    private LocalDate earliestShareDate(
            Map<String, List<SharePoint>> sharesByFund
    ) {
        return sharesByFund.values().stream()
                .flatMap(List::stream)
                .map(SharePoint::tradeDate)
                .min(LocalDate::compareTo)
                .orElse(null);
    }

    private LocalDateTime later(
            LocalDateTime left,
            LocalDateTime right
    ) {
        return left.isAfter(right) ? left : right;
    }

    private static final class MarginAggregate {
        private BigDecimal financingBalance = BigDecimal.ZERO;
        private BigDecimal securitiesLendingBalance = BigDecimal.ZERO;
        private BigDecimal financingBuy = BigDecimal.ZERO;
        private BigDecimal financingRepay = BigDecimal.ZERO;
        private BigDecimal combinedBalance = BigDecimal.ZERO;
        private final Map<String, Map<String, BigDecimal>>
                metricsByExchange = new LinkedHashMap<>();

        private void add(
                String exchange,
                Map<String, Object> row
        ) {
            BigDecimal exchangeBalance = number(row, "rzye");
            financingBalance = financingBalance.add(exchangeBalance);
            Map<String, BigDecimal> exchangeMetrics =
                    metricsByExchange.computeIfAbsent(
                            exchange,
                            ignored -> new LinkedHashMap<>());
            for (String metric : MARGIN_METRICS) {
                exchangeMetrics.merge(
                        metric, number(row, metric),
                        BigDecimal::add);
            }
            securitiesLendingBalance =
                    securitiesLendingBalance.add(
                            number(row, "rqye"));
            financingBuy = financingBuy.add(
                    number(row, "rzmre"));
            financingRepay = financingRepay.add(
                    number(row, "rzche"));
            combinedBalance = combinedBalance.add(
                    number(row, "rzrqye"));
        }

        private boolean hasExchange(String exchange) {
            return metricsByExchange.containsKey(exchange);
        }

        private BigDecimal metric(
                String exchange,
                String metric
        ) {
            return metricsByExchange.get(exchange).get(metric);
        }

        private static BigDecimal number(
                Map<String, Object> row,
                String key
        ) {
            Object value = row.get(key);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing TuShare decimal field " + key);
            }
            try {
                return value instanceof BigDecimal decimal
                        ? decimal
                        : new BigDecimal(value.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "invalid TuShare decimal field " + key);
            }
        }
    }

    private static final class MarginDetailAggregate {
        private final Map<String, Integer> countByExchange =
                new LinkedHashMap<>();
        private final Map<String, Map<String, BigDecimal>>
                metricsByExchange = new LinkedHashMap<>();

        private void add(
                String exchange,
                Map<String, Object> row
        ) {
            countByExchange.merge(exchange, 1, Integer::sum);
            Map<String, BigDecimal> exchangeMetrics =
                    metricsByExchange.computeIfAbsent(
                            exchange,
                            ignored -> new LinkedHashMap<>());
            for (String metric : MARGIN_METRICS) {
                exchangeMetrics.merge(
                        metric, MarginAggregate.number(row, metric),
                        BigDecimal::add);
            }
        }

        private int count(String exchange) {
            return countByExchange.getOrDefault(exchange, 0);
        }

        private int count() {
            return countByExchange.values().stream()
                    .mapToInt(Integer::intValue).sum();
        }

        private BigDecimal metric(
                String exchange,
                String metric
        ) {
            return metricsByExchange
                    .getOrDefault(exchange, Map.of())
                    .getOrDefault(metric, BigDecimal.ZERO);
        }
    }

    private record TradingCalendar(List<LocalDate> openDays) {

        private TradingCalendar {
            openDays = openDays == null
                    ? List.of() : List.copyOf(openDays);
        }

        private boolean isOpen(LocalDate date) {
            return openDays.contains(date);
        }

        private LocalDate previousOpenBefore(LocalDate date) {
            return openDays.stream()
                    .filter(openDay -> openDay.isBefore(date))
                    .max(LocalDate::compareTo).orElse(null);
        }

        private LocalDate requiredNextOpenAfter(LocalDate date) {
            return openDays.stream()
                    .filter(openDay -> openDay.isAfter(date))
                    .min(LocalDate::compareTo)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "trade_cal has no next open day after "
                                    + date));
        }

        private List<LocalDate> openDays(
                LocalDate start,
                LocalDate end
        ) {
            return openDays.stream()
                    .filter(date -> !date.isBefore(start)
                            && !date.isAfter(end))
                    .toList();
        }
    }

    private static final class EtfAggregate {
        private BigDecimal netFlow = BigDecimal.ZERO;
        private BigDecimal referenceAssets = BigDecimal.ZERO;
        private BigDecimal closeWeightedAssets = BigDecimal.ZERO;
        private final List<Map<String, Object>> fundContributions =
                new ArrayList<>();
        private int fundCount;
        private LocalDateTime availableAt =
                LocalDateTime.MIN;

        private void add(
                String fundCode,
                BigDecimal flow,
                BigDecimal assets,
                BigDecimal close,
                BigDecimal unitNav,
                LocalDate navAnnouncementDate,
                LocalDateTime availability
        ) {
            netFlow = netFlow.add(flow);
            referenceAssets = referenceAssets.add(assets);
            closeWeightedAssets =
                    closeWeightedAssets.add(close.multiply(assets));
            fundContributions.add(Map.of(
                    "fundCode", fundCode,
                    "netFlow", flow,
                    "referenceAssets", assets,
                    "close", close,
                    "unitNav", unitNav,
                    "navAnnouncementDate",
                    navAnnouncementDate));
            fundCount++;
            availableAt = availableAt.isAfter(availability)
                    ? availableAt : availability;
        }

        private BigDecimal weightedClose() {
            if (referenceAssets.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return closeWeightedAssets.divide(
                    referenceAssets, 8,
                    RoundingMode.HALF_UP)
                    .stripTrailingZeros();
        }

        private List<Map<String, Object>> contributions() {
            return fundContributions.stream()
                    .sorted(Comparator.comparing(
                            contribution -> contribution
                                    .get("fundCode").toString()))
                    .map(Map::copyOf)
                    .toList();
        }
    }

    private record NavPoint(
            BigDecimal unitNav,
            LocalDate announcementDate
    ) {
    }

    private record SharePoint(
            LocalDate tradeDate,
            BigDecimal share
    ) {
    }
}
