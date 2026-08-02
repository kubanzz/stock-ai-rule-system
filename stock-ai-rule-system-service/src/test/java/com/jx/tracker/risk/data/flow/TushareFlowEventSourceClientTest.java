package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TushareFlowEventSourceClientTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T12:00:00Z"), SHANGHAI);
    private static final RiskObjectKey MARKET =
            new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey STOCK =
            new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private static final LocalDate START = LocalDate.of(2026, 7, 1);
    private static final LocalDate END = LocalDate.of(2026, 7, 18);

    @Test
    void marginAndDetailMapToAuditedMarketFinancingRecords() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260715", true),
                        tradeCalendarRow("20260716", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260718", false),
                        tradeCalendarRow("20260719", false),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin",
                        marginRow("SSE", "20260715", "80", "10", "7", "4", "90"),
                        marginRow("SZSE", "20260715", "170", "20", "11", "8", "190"),
                        marginRow("SSE", "20260716", "100", "20", "15", "5", "120"),
                        marginRow("SZSE", "20260716", "200", "30", "25", "10", "230"),
                        marginRow("SSE", "20260717", "90", "10", "8", "18", "100"),
                        marginRow("SZSE", "20260717", "180", "20", "12", "30", "200"),
                        marginRow("BSE", "20260717", "999", "99", "88", "77", "1098"));
                case "margin_detail" -> switch (
                        query.params().get("start_date").toString()) {
                    case "20260715" -> response("margin_detail",
                                        marginDetailRow(
                                                "600519.SH", "20260715",
                                                "80", "7", "4", "10", "90"),
                                        marginDetailRow(
                                                "000001.SZ", "20260715",
                                                "170", "11", "8", "20", "190"));
                    case "20260716" -> response("margin_detail",
                                        marginDetailRow(
                                                "600519.SH", "20260716",
                                                "100", "15", "5", "20", "120"),
                                        marginDetailRow(
                                                "000001.SZ", "20260716",
                                                "200", "25", "10", "30", "230"));
                    case "20260717" -> response("margin_detail",
                                        marginDetailRow(
                                                "600519.SH", "20260717",
                                                "90", "8", "18", "10", "100"),
                                        marginDetailRow(
                                                "000001.SZ", "20260717",
                                                "180", "12", "30", "20", "200"),
                                        marginDetailRow(
                                                "430047.BJ", "20260717",
                                                "999", "88", "77", "99", "1098"));
                    default -> throw new AssertionError(
                            query.params());
                };
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(
                                FlowEventDataset.MARGIN_FINANCING,
                                List.of(MARKET),
                                LocalDate.of(2026, 7, 16),
                                LocalDate.of(2026, 7, 17)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isTrue();
        assertThat(result.records()).hasSize(2);
        assertThat(result.records()).element(1).satisfies(record -> {
            assertThat(record.object()).isEqualTo(MARKET);
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 17));
            assertThat(record.value()).isEqualByComparingTo("270");
            assertThat(record.attributes())
                    .containsEntry("previousBalance", new BigDecimal("300"))
                    .containsEntry("referenceBalance", new BigDecimal("300"))
                    .containsEntry("financingBuy", new BigDecimal("20"))
                    .containsEntry("financingRepay", new BigDecimal("48"))
                    .containsEntry("detailRowCount", 2)
                    .containsEntry("formulaVersion", "margin-market-sum-rzye-v1");
            assertThat(record.observedAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 17, 15, 0));
            assertThat(record.availableAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 20, 8, 30));
        });
        ArgumentCaptor<TushareRiskRequest> captor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(5))
                .query(captor.capture());
        assertThat(captor.getAllValues()).filteredOn(
                        query -> query.apiName().equals("margin_detail"))
                .allSatisfy(query -> assertThat(query.params())
                        .containsOnlyKeys("start_date", "end_date"))
                .extracting(query -> query.params().get("start_date"))
                .containsExactly(
                        "20260715", "20260716", "20260717");
        assertThat(captor.getAllValues()).filteredOn(
                        query -> query.apiName().equals("margin"))
                .singleElement().satisfies(query -> assertThat(
                                query.params())
                        .containsEntry("start_date", "20260715")
                        .containsEntry("end_date", "20260717"));
    }

    @Test
    void marginFailsClosedForMissingExchangeAndEmptyDetail() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260630", true),
                        tradeCalendarRow("20260716", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin",
                        marginRow("SSE", "20260630",
                                "80", "10", "7", "4", "90"),
                        marginRow("SZSE", "20260630",
                                "170", "20", "11", "8", "190"),
                        marginRow("SSE", "20260716",
                                "100", "20", "15", "5", "120"),
                        marginRow("SSE", "20260717",
                                "90", "10", "8", "18", "100"),
                        marginRow("SZSE", "20260717",
                                "180", "20", "12", "30", "200"));
                case "margin_detail" -> response("margin_detail");
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(
                                FlowEventDataset.MARGIN_FINANCING,
                                List.of(MARKET),
                                LocalDate.of(2026, 7, 16),
                                LocalDate.of(2026, 7, 17)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("20260716")
                .contains("SZSE")
                .contains("margin_detail");
    }

    @Test
    void marginFailsClosedWhenAnyAuditedMetricDiffers() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260716", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin",
                        marginRow("SSE", "20260716",
                                "100", "20", "15", "5", "120"),
                        marginRow("SZSE", "20260716",
                                "200", "30", "25", "10", "230"),
                        marginRow("SSE", "20260717",
                                "90", "10", "8", "18", "100"),
                        marginRow("SZSE", "20260717",
                                "180", "20", "12", "30", "200"));
                case "margin_detail" -> response("margin_detail",
                        marginDetailRow("600519.SH", "20260716",
                                "100", "15", "5", "20", "120"),
                        marginDetailRow("000001.SZ", "20260716",
                                "200", "25", "10", "30", "230"),
                        marginDetailRow("600519.SH", "20260717",
                                "90", "8", "18", "10", "100"),
                        marginDetailRow("000001.SZ", "20260717",
                                "180", "12", "30", "999", "200"));
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.MARGIN_FINANCING,
                        List.of(MARKET),
                        LocalDate.of(2026, 7, 17),
                        LocalDate.of(2026, 7, 17)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.failureReason())
                .contains("SZSE")
                .contains("rqye");
    }

    @Test
    void marginDailySlicesAllowTwoDaysWhoseCombinedRowsExceedLimit() {
        List<Map<String, Object>> fourThousandRows16 =
                zeroMarginDetailRows("20260716", 4_000);
        List<Map<String, Object>> fourThousandRows17 =
                zeroMarginDetailRows("20260717", 4_000);
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260715", true),
                        tradeCalendarRow("20260716", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260718", false),
                        tradeCalendarRow("20260719", false),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin",
                        zeroMarginRow("SSE", "20260715"),
                        zeroMarginRow("SZSE", "20260715"),
                        zeroMarginRow("SSE", "20260716"),
                        zeroMarginRow("SZSE", "20260716"),
                        zeroMarginRow("SSE", "20260717"),
                        zeroMarginRow("SZSE", "20260717"));
                case "margin_detail" -> {
                    String date = query.params()
                            .get("start_date").toString();
                    if ("20260715".equals(date)) {
                        yield response("margin_detail",
                                marginDetailRow(
                                        "600519.SH", date,
                                        "0", "0", "0", "0", "0"),
                                marginDetailRow(
                                        "000001.SZ", date,
                                        "0", "0", "0", "0", "0"));
                    }
                    yield new TushareRiskResponse(
                            "margin_detail", List.of(),
                            "20260716".equals(date)
                                    ? fourThousandRows16
                                    : fourThousandRows17);
                }
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.MARGIN_FINANCING,
                        List.of(MARKET),
                        LocalDate.of(2026, 7, 16),
                        LocalDate.of(2026, 7, 17)));

        assertThat(result.historyComplete()).isTrue();
        assertThat(result.records()).hasSize(2);
        ArgumentCaptor<TushareRiskRequest> captor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(5))
                .query(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(query -> "margin_detail".equals(
                        query.apiName()))
                .extracting(query -> query.params()
                        .get("start_date"))
                .containsExactly(
                        "20260715", "20260716", "20260717");
    }

    @Test
    void marginFailsClosedWhenWarmupCalendarIntervalHasAGap() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260714", true),
                        tradeCalendarRow("20260716", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260718", false),
                        tradeCalendarRow("20260719", false),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin",
                        zeroMarginRow("SSE", "20260714"),
                        zeroMarginRow("SZSE", "20260714"),
                        zeroMarginRow("SSE", "20260716"),
                        zeroMarginRow("SZSE", "20260716"),
                        zeroMarginRow("SSE", "20260717"),
                        zeroMarginRow("SZSE", "20260717"));
                case "margin_detail" -> {
                    String date = query.params()
                            .get("start_date").toString();
                    yield response("margin_detail",
                            marginDetailRow(
                                    "600519.SH", date,
                                    "0", "0", "0", "0", "0"),
                            marginDetailRow(
                                    "000001.SZ", date,
                                    "0", "0", "0", "0", "0"));
                }
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.MARGIN_FINANCING,
                        List.of(MARKET),
                        LocalDate.of(2026, 7, 16),
                        LocalDate.of(2026, 7, 17)));

        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("trade_cal missing dependency dates")
                .contains("2026-07-15");
    }

    @Test
    void marginMissingClosedCalendarDayFailsClosed() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260715", true),
                        tradeCalendarRow("20260716", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin",
                        zeroMarginRow("SSE", "20260715"),
                        zeroMarginRow("SZSE", "20260715"),
                        zeroMarginRow("SSE", "20260716"),
                        zeroMarginRow("SZSE", "20260716"),
                        zeroMarginRow("SSE", "20260717"),
                        zeroMarginRow("SZSE", "20260717"));
                case "margin_detail" -> {
                    String date = query.params()
                            .get("start_date").toString();
                    yield response("margin_detail",
                            marginDetailRow(
                                    "600519.SH", date,
                                    "0", "0", "0", "0", "0"),
                            marginDetailRow(
                                    "000001.SZ", date,
                                    "0", "0", "0", "0", "0"));
                }
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.MARGIN_FINANCING,
                        List.of(MARKET),
                        LocalDate.of(2026, 7, 16),
                        LocalDate.of(2026, 7, 18)));

        assertThat(result.historyComplete()).isFalse();
        assertThat(result.failureReason())
                .contains("trade_cal missing dependency dates")
                .contains("2026-07-18");
    }

    @Test
    void etfShareDeltaUsesUnitNavAndDailyCloseOnlyAsAnAuditedReference() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" -> response(
                        "etf_basic", etfBasicRow(
                                "510300.SH", "L"));
                case "fund_basic" -> response(
                        "fund_basic", fundBasicRow(
                                "510300.SH", "沪深300"));
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", "510300.SH", "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", "510300.SH", "trade_date", "20260717",
                                "fd_share", new BigDecimal("110")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", "510300.SH", "ann_date", "20260717",
                                "nav_date", "20260716", "unit_nav", new BigDecimal("1.10")),
                        Map.of("ts_code", "510300.SH", "ann_date", "20260717",
                                "nav_date", "20260717", "unit_nav", new BigDecimal("1.10")),
                        Map.of("ts_code", "510300.SH", "ann_date", "20260718",
                                "nav_date", "20260717", "unit_nav", new BigDecimal("1.20")),
                        Map.of("ts_code", "510300.SH", "ann_date", "20260720",
                                "nav_date", "20260717", "unit_nav", new BigDecimal("9.99")));
                case "fund_daily" -> response("fund_daily",
                        Map.of("ts_code", "510300.SH", "trade_date", "20260716",
                                "close", new BigDecimal("1.11")),
                        Map.of("ts_code", "510300.SH", "trade_date", "20260717",
                                "close", new BigDecimal("1.22")),
                        Map.of("ts_code", "510300.SH", "trade_date", "20260717",
                                "close", new BigDecimal("1.22")));
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 17));
            assertThat(record.value()).isEqualByComparingTo("120000");
            assertThat(record.unit()).isEqualTo("currency");
            assertThat(record.eventCode()).isEqualTo("etf_redemption_flow");
            assertThat(record.availableAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 20, 8, 30));
            assertThat(record.attributes())
                    .containsEntry("referenceAssets", new BigDecimal("1320000"))
                    .containsEntry("shareUnitMultiplier", new BigDecimal("10000"))
                    .containsEntry("fundDailyClose", new BigDecimal("1.22"))
                    .containsEntry(
                            "fundDailyCloseWeightedAverage",
                            new BigDecimal("1.22"))
                    .containsEntry("formulaVersion", "fund-share-delta-times-unit-nav-v1")
                    .containsEntry("secondaryMarketAmountUsed", false)
                    .containsEntry("fundContributionCount", 1)
                    .containsKeys(
                            "fundContributionHash",
                            "fundContributionTop")
                    .doesNotContainKey("fundContributions");
        });
    }

    @Test
    void etfCloseAuditIsDeterministicAndAssetWeighted() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            if ("etf_basic".equals(query.apiName())) {
                return response("etf_basic",
                        etfBasicRow("510500.SH", "L"),
                        etfBasicRow("510300.SH", "L"));
            }
            if ("fund_basic".equals(query.apiName())) {
                return response("fund_basic",
                        fundBasicRow("510500.SH", "中证500ETF"),
                        fundBasicRow("510300.SH", "沪深300ETF"));
            }
            String code = query.params().get("ts_code").toString();
            BigDecimal nav = "510300.SH".equals(code)
                    ? BigDecimal.ONE : new BigDecimal("2");
            BigDecimal close = "510300.SH".equals(code)
                    ? BigDecimal.ONE : new BigDecimal("3");
            return switch (query.apiName()) {
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", code,
                                "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", code,
                                "trade_date", "20260717",
                                "fd_share", new BigDecimal("110")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", code,
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", nav));
                case "fund_daily" -> response("fund_daily",
                        Map.of("ts_code", code,
                                "trade_date", "20260717",
                                "close", close));
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.records()).singleElement()
                .satisfies(record -> {
                    assertThat(record.value())
                            .isEqualByComparingTo("300000");
                    assertThat(record.attributes().get(
                            "fundDailyCloseWeightedAverage"))
                            .isEqualTo(new BigDecimal("2.33333333"));
                    assertThat(((List<?>) record.attributes().get(
                            "fundContributionTop")).stream()
                            .map(value -> ((Map<?, ?>) value)
                                    .get("fundCode").toString())
                            .toList())
                            .containsExactly(
                                    "510500.SH", "510300.SH");
                });
    }

    @Test
    void etfDuplicateNavOrDailyConflictFailsClosed() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" -> response(
                        "etf_basic", etfBasicRow(
                                "510300.SH", "L"));
                case "fund_basic" -> response(
                        "fund_basic", fundBasicRow(
                                "510300.SH", "沪深300ETF"));
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260717",
                                "fd_share", new BigDecimal("110")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", "510300.SH",
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", new BigDecimal("1.2")),
                        Map.of("ts_code", "510300.SH",
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", new BigDecimal("1.3")));
                case "fund_daily" -> response("fund_daily",
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260717",
                                "close", new BigDecimal("1.2")),
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260717",
                                "close", new BigDecimal("1.3")));
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("conflicting fund_nav")
                .contains("conflicting fund_daily");
    }

    @Test
    void etfSlicesARealisticConfirmedUniverseByFundAndUsesIndependentLimits() {
        int fundCount = 120;
        List<Map<String, Object>> universe = new ArrayList<>();
        for (int index = 0; index < fundCount; index++) {
            universe.add(fundBasicRow(
                    "51%04d.SH".formatted(index),
                    "代表性ETF-%03d".formatted(index)));
        }
        Set<String> queriedFunds = new HashSet<>();
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            if ("etf_basic".equals(query.apiName())) {
                return new TushareRiskResponse(
                        "etf_basic", List.of(),
                        universe.stream()
                                .map(row -> etfBasicRow(
                                        row.get("ts_code").toString(),
                                        "L"))
                                .toList());
            }
            if ("fund_basic".equals(query.apiName())) {
                return new TushareRiskResponse(
                        "fund_basic", List.of(), universe);
            }
            String code = query.params().get("ts_code").toString();
            queriedFunds.add(code);
            return switch (query.apiName()) {
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", code,
                                "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", code,
                                "trade_date", "20260717",
                                "fd_share", new BigDecimal("101")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", code,
                                "ann_date", "20260717",
                                "nav_date", "20260716",
                                "unit_nav", BigDecimal.ONE),
                        Map.of("ts_code", code,
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", BigDecimal.ONE));
                case "fund_daily" -> {
                    List<Map<String, Object>> dailyRows =
                            new ArrayList<>();
                    for (int row = 0; row < 2_000; row++) {
                        dailyRows.add(Map.of(
                                "ts_code", code,
                                "trade_date",
                                row == 1_999
                                        ? "20260717"
                                        : "20260716",
                                "close", BigDecimal.ONE));
                    }
                    yield new TushareRiskResponse(
                            "fund_daily", List.of(), dailyRows);
                }
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(
                                FlowEventDataset.ETF_FUND_FLOW,
                                List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isTrue();
        assertThat(result.records()).singleElement()
                .satisfies(record -> assertThat(record.attributes())
                        .containsEntry("universeFundCount", fundCount)
                        .containsEntry("coveredFundCount", fundCount));
        assertThat(queriedFunds).hasSize(fundCount);
        ArgumentCaptor<TushareRiskRequest> captor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient,
                org.mockito.Mockito.times(5 + fundCount * 3))
                .query(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(query -> List.of(
                                "fund_share", "fund_nav",
                                "fund_daily")
                        .contains(query.apiName()))
                .allSatisfy(query -> assertThat(query.params())
                        .containsKeys(
                                "ts_code", "start_date", "end_date"));
    }

    @Test
    void etfFailsClosedWhenAnyConfirmedFundLacksContinuity() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            if ("etf_basic".equals(query.apiName())) {
                return response("etf_basic",
                        etfBasicRow("510300.SH", "L"),
                        etfBasicRow("510500.SH", "L"));
            }
            if ("fund_basic".equals(query.apiName())) {
                return response("fund_basic",
                        fundBasicRow("510300.SH", "沪深300ETF"),
                        fundBasicRow("510500.SH", "中证500ETF"),
                        fundBasicRow("160000.SZ", "非交易型基金"));
            }
            String code = query.params().get("ts_code").toString();
            if ("510500.SH".equals(code)) {
                return response(query.apiName());
            }
            return switch (query.apiName()) {
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", code,
                                "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", code,
                                "trade_date", "20260717",
                                "fd_share", new BigDecimal("101")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", code,
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", BigDecimal.ONE));
                case "fund_daily" -> response("fund_daily",
                        Map.of("ts_code", code,
                                "trade_date", "20260717",
                                "close", BigDecimal.ONE));
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(
                                FlowEventDataset.ETF_FUND_FLOW,
                                List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("510500.SH")
                .contains("continuity");
    }

    @Test
    void delistedEtfRemainsInItsHistoricalPitWindow() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" ->
                        "D".equals(query.params().get("list_status"))
                                ? response(
                                        "etf_basic",
                                        etfBasicRow(
                                                "510900.SH", "D"))
                                : response("etf_basic");
                case "fund_basic" -> response(
                        "fund_basic",
                        fundBasicRow(
                                "510900.SH", "历史基金短名",
                                "20200101", "20260717"));
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", "510900.SH",
                                "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", "510900.SH",
                                "trade_date", "20260717",
                                "fd_share", new BigDecimal("110")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", "510900.SH",
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", BigDecimal.ONE));
                case "fund_daily" -> response("fund_daily",
                        Map.of("ts_code", "510900.SH",
                                "trade_date", "20260717",
                                "close", BigDecimal.ONE));
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.historyComplete()).isTrue();
        assertThat(result.records()).singleElement()
                .satisfies(record -> assertThat(record.attributes())
                        .containsEntry("universeFundCount", 1)
                        .containsEntry("coveredFundCount", 1));
    }

    @Test
    void etfCatalogMissingFundMetadataFailsClosed() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" -> response(
                        "etf_basic",
                        etfBasicRow("510300.SH", "L"));
                case "fund_basic" -> response("fund_basic");
                default -> throw new AssertionError(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(
                        RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.failureReason())
                .contains("510300.SH")
                .contains("missing fund_basic date metadata");
    }

    @Test
    void emptyEtfCatalogFailsClosed() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return "trade_cal".equals(query.apiName())
                    ? standardTradeCalendar()
                    : response(query.apiName());
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(
                        RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.failureReason())
                .contains("etf_basic returned no ETF catalog rows");
    }

    @Test
    void etfCatalogBoundaryFailsClosed() {
        List<Map<String, Object>> rows =
                new ArrayList<>();
        for (int index = 0; index < 5_000; index++) {
            rows.add(etfBasicRow("510300.SH", "L"));
        }
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" ->
                        "L".equals(query.params().get("list_status"))
                                ? new TushareRiskResponse(
                                        "etf_basic", List.of(), rows)
                                : response("etf_basic");
                case "fund_basic" -> response(
                        "fund_basic", fundBasicRow(
                                "510300.SH", "短名"));
                default -> response(query.apiName());
            };
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(
                        RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.failureReason())
                .contains("etf_basic")
                .contains("5000");
    }

    @Test
    void etfCatalogPermissionFailureIsUnavailable() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            throw failure(
                    TushareRiskException.Category.PERMISSION,
                    "etf_basic");
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(result.failureReason())
                .isEqualTo("etf_basic permission failure");
    }

    @Test
    void etfCatalogIsReusedForTheSamePitWindow() {
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" -> response(
                        "etf_basic",
                        etfBasicRow("510300.SH", "L"));
                case "fund_basic" -> response(
                        "fund_basic",
                        fundBasicRow("510300.SH", "短名"));
                case "fund_share" -> response("fund_share",
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260716",
                                "fd_share", new BigDecimal("100")),
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260717",
                                "fd_share", new BigDecimal("110")));
                case "fund_nav" -> response("fund_nav",
                        Map.of("ts_code", "510300.SH",
                                "ann_date", "20260718",
                                "nav_date", "20260717",
                                "unit_nav", BigDecimal.ONE));
                case "fund_daily" -> response("fund_daily",
                        Map.of("ts_code", "510300.SH",
                                "trade_date", "20260717",
                                "close", BigDecimal.ONE));
                default -> throw new AssertionError(query.apiName());
            };
        });
        TushareFlowEventSourceClient client =
                new TushareFlowEventSourceClient(httpClient, CLOCK);
        FlowEventSourceRequest request = request(
                FlowEventDataset.ETF_FUND_FLOW,
                List.of(MARKET));

        assertThat(client.fetch(request).historyComplete()).isTrue();
        assertThat(client.fetch(request).historyComplete()).isTrue();

        ArgumentCaptor<TushareRiskRequest> captor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(12))
                .query(captor.capture());
        assertThat(captor.getAllValues().stream()
                .filter(query -> "etf_basic".equals(
                        query.apiName())).count()).isEqualTo(3);
        assertThat(captor.getAllValues().stream()
                .filter(query -> "fund_basic".equals(
                        query.apiName())).count()).isEqualTo(1);
    }

    @Test
    void forecastVipSlicesByQuarterAndUsesAnnouncementAvailabilityWithStrictCodeFiltering() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            return "20260630".equals(query.params().get("period"))
                    ? response("forecast_vip",
                            forecastRow("600519.SH", "-50", "-30",
                                    "预计净利润下降", "需求波动"),
                            forecastRow("000001.SZ", "-80", "-60",
                                    "wrong object", "wrong object"))
                    : response("forecast_vip");
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record.value()).isEqualByComparingTo("-40");
            assertThat(record.availableAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 17, 0, 0));
            assertThat(record.attributes())
                    .containsEntry("sourceApi", "forecast_vip")
                    .containsEntry("formulaVersion", "forecast-range-midpoint-v1")
                    .containsEntry("pChangeMin", new BigDecimal("-50"))
                    .containsEntry("pChangeMax", new BigDecimal("-30"))
                    .containsEntry(
                            "last_parent_net",
                            new BigDecimal("900"))
                    .containsEntry("adverse", true);
        });
        ArgumentCaptor<TushareRiskRequest> captor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(4))
                .query(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(query -> "forecast_vip".equals(
                        query.apiName()))
                .allSatisfy(query -> {
                    assertThat(query.apiName()).isEqualTo("forecast_vip");
                    assertThat(query.params())
                            .containsOnlyKeys("period")
                            .doesNotContainKeys("ts_code", "start_date", "end_date");
                })
                .extracting(query -> query.params().get("period"))
                .containsExactly("20260630", "20260930", "20261231");
    }

    @Test
    void unlockAndReductionPreserveAnnouncementPointInTimeAndModifierFields() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "share_float" -> response("share_float", Map.of(
                        "ts_code", "600519.SH",
                        "ann_date", "20260710",
                        "float_date", "20260810",
                        "float_share", new BigDecimal("250000"),
                        "float_ratio", new BigDecimal("1.25"),
                        "holder_name", "示例股东",
                        "share_type", "定增股份"));
                case "stk_holdertrade" -> response("stk_holdertrade",
                        holderTradeRow("DE", "20260715", "20260710", "20260714",
                                "30000", "0.45"),
                        holderTradeRow("IN", "20260715", "20260710", "20260714",
                                "10000", "0.10"));
                default -> throw new AssertionError(query.apiName());
            };
        });
        TushareFlowEventSourceClient client =
                new TushareFlowEventSourceClient(httpClient, CLOCK);

        FlowEventSourceBatch unlock = client.fetch(
                request(FlowEventDataset.SHARE_UNLOCK, List.of(STOCK)));
        FlowEventSourceBatch reduction = client.fetch(
                request(FlowEventDataset.SHARE_REDUCTION, List.of(STOCK)));

        assertThat(unlock.records()).singleElement().satisfies(record -> {
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 8, 10));
            assertThat(record.observedAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 10, 0, 0));
            assertThat(record.unit()).isEqualTo("shares");
            assertThat(record.value())
                    .isEqualByComparingTo("250000");
            assertThat(record.availableAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 13, 0, 0));
            assertThat(record.attributes())
                    .containsEntry("modifierRatio", new BigDecimal("1.25"))
                    .containsEntry("scheduled", true);
        });
        assertThat(reduction.records()).singleElement().satisfies(record -> {
            assertThat(record.value()).isEqualByComparingTo("30000");
            assertThat(record.availableAt())
                    .isEqualTo(LocalDateTime.of(2026, 7, 16, 0, 0));
            assertThat(record.attributes())
                    .containsEntry("actualReduction", true)
                    .containsEntry("modifierRatio", new BigDecimal("0.45"))
                    .containsEntry("direction", "DE");
        });
    }

    @Test
    void successfulEmptyEventQueryIsValidZeroAndDoesNotInventRecords() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return "trade_cal".equals(query.apiName())
                    ? standardTradeCalendar()
                    : response("forecast_vip");
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(result.historyComplete()).isTrue();
        assertThat(result.records()).isEmpty();
        assertThat(result.nextCursor()).isEqualTo("earnings_forecast:2026-07-18");
    }

    @Test
    void publishedFactWaitsForNextExchangeOpenDayAcrossHoliday() {
        Map<String, Object> row = new java.util.LinkedHashMap<>(
                forecastRow(
                        "600519.SH", "-50", "-30",
                        "预计净利润下降", "需求波动"));
        row.put("ann_date", "20260925");
        row.put("end_date", "20260930");
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return response("trade_cal",
                        tradeCalendarRow("20260925", true),
                        tradeCalendarRow("20260926", false),
                        tradeCalendarRow("20260927", false),
                        tradeCalendarRow("20260928", false),
                        tradeCalendarRow("20260929", true));
            }
            return "20260930".equals(
                    query.params().get("period"))
                    ? new TushareRiskResponse(
                            "forecast_vip", List.of(),
                            List.of(row))
                    : response("forecast_vip");
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(
                        httpClient, CLOCK).fetch(request(
                        FlowEventDataset.EARNINGS_FORECAST,
                        List.of(STOCK),
                        LocalDate.of(2026, 9, 25),
                        LocalDate.of(2026, 10, 1)));

        assertThat(result.records()).singleElement()
                .satisfies(record -> {
                    assertThat(record.observedAt())
                            .isEqualTo(LocalDateTime.of(
                                    2026, 9, 25, 0, 0));
                    assertThat(record.availableAt())
                            .isEqualTo(LocalDateTime.of(
                                    2026, 9, 29, 0, 0));
                    assertThat(record.availableAt())
                            .isAfterOrEqualTo(record.observedAt());
                });
    }

    @Test
    void forecastVipQuarterAtRowLimitIsPartialWithoutCheckpoint() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 2000; index++) {
            rows.add(forecastRow(
                    "600519.SH", "-50", "-30",
                    "预计净利润下降", "需求波动"));
        }
        TushareRiskHttpClient httpClient =
                mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            return "20260630".equals(query.params().get("period"))
                    ? new TushareRiskResponse(
                            "forecast_vip", List.of(), rows)
                    : response("forecast_vip");
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(
                                FlowEventDataset.EARNINGS_FORECAST,
                                List.of(STOCK)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("forecast_vip")
                .contains("20260630")
                .contains("2000");
    }

    @Test
    void documentedRowBoundaryFailsClosedWithoutCheckpoint() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < 3000; index++) {
            rows.add(holderTradeRow(
                    "DE", "20260715", "20260710", "20260714", "1", "0.01"));
        }
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return "trade_cal".equals(query.apiName())
                    ? standardTradeCalendar()
                    : new TushareRiskResponse(
                            "stk_holdertrade", List.of(), rows);
        });

        FlowEventSourceBatch result =
                new TushareFlowEventSourceClient(httpClient, CLOCK)
                        .fetch(request(FlowEventDataset.SHARE_REDUCTION, List.of(STOCK)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("stk_holdertrade")
                .contains("3000");
    }

    @Test
    void fundAndMarginDetailBoundariesAlsoFailClosed() {
        List<Map<String, Object>> fundRows = new ArrayList<>();
        for (int index = 0; index < 2000; index++) {
            fundRows.add(Map.of(
                    "ts_code", "510300.SH",
                    "trade_date", "20260717",
                    "fd_share", new BigDecimal("100")));
        }
        TushareRiskHttpClient fundHttpClient =
                mock(TushareRiskHttpClient.class);
        when(fundHttpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> standardTradeCalendar();
                case "etf_basic" -> response(
                        "etf_basic", etfBasicRow(
                                "510300.SH", "L"));
                case "fund_basic" -> response(
                        "fund_basic", fundBasicRow(
                                "510300.SH", "沪深300ETF"));
                case "fund_share" ->
                        new TushareRiskResponse(
                                "fund_share", List.of(), fundRows);
                default -> response(query.apiName());
            };
        });
        FlowEventSourceBatch fundResult =
                new TushareFlowEventSourceClient(
                        fundHttpClient, CLOCK).fetch(request(
                        FlowEventDataset.ETF_FUND_FLOW,
                        List.of(MARKET)));
        assertThat(fundResult.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(fundResult.nextCursor()).isNull();
        assertThat(fundResult.failureReason()).contains("2000");

        List<Map<String, Object>> detailRows = new ArrayList<>();
        for (int index = 0; index < 6000; index++) {
            detailRows.add(marginDetailRow(
                    "600519.SH", "20260717",
                    "1", "1", "1", "1", "2"));
        }
        TushareRiskHttpClient marginHttpClient =
                mock(TushareRiskHttpClient.class);
        when(marginHttpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            return switch (query.apiName()) {
                case "trade_cal" -> response("trade_cal",
                        tradeCalendarRow("20260630", true),
                        tradeCalendarRow("20260717", true),
                        tradeCalendarRow("20260720", true));
                case "margin" -> response("margin", marginRow(
                        "SSE", "20260717",
                        "100", "20", "15", "5", "120"));
                case "margin_detail" ->
                        new TushareRiskResponse(
                                "margin_detail", List.of(),
                                detailRows);
                default -> throw new AssertionError(query.apiName());
            };
        });
        FlowEventSourceBatch marginResult =
                new TushareFlowEventSourceClient(
                        marginHttpClient, CLOCK).fetch(request(
                        FlowEventDataset.MARGIN_FINANCING,
                        List.of(MARKET)));
        assertThat(marginResult.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(marginResult.historyComplete()).isFalse();
        assertThat(marginResult.nextCursor()).isNull();
        assertThat(marginResult.failureReason()).contains("6000");
    }

    @Test
    void permissionFailureIsSanitizedAndAnnouncementNeverCallsTushare() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            if ("trade_cal".equals(query.apiName())) {
                return standardTradeCalendar();
            }
            throw failure(
                    TushareRiskException.Category.PERMISSION,
                    "forecast_vip");
        });
        TushareFlowEventSourceClient client =
                new TushareFlowEventSourceClient(httpClient, CLOCK);

        FlowEventSourceBatch failed = client.fetch(
                request(FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK)));

        assertThat(failed.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(failed.failureReason())
                .isEqualTo("forecast_vip permission failure")
                .doesNotContain("raw response", "token");

        TushareRiskHttpClient announcementHttpClient =
                mock(TushareRiskHttpClient.class);
        FlowEventSourceBatch announcement =
                new TushareFlowEventSourceClient(announcementHttpClient, CLOCK)
                        .fetch(request(
                                FlowEventDataset.STOCK_ANNOUNCEMENT,
                                List.of(STOCK)));
        assertThat(announcement.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(announcement.failureReason())
                .contains("CNInfo/AKTools");
        verify(announcementHttpClient, never()).query(any());
    }

    private static FlowEventSourceRequest request(
            FlowEventDataset dataset,
            List<RiskObjectKey> objects
    ) {
        return request(dataset, objects, START, END);
    }

    private static FlowEventSourceRequest request(
            FlowEventDataset dataset,
            List<RiskObjectKey> objects,
            LocalDate start,
            LocalDate end
    ) {
        return new FlowEventSourceRequest(dataset, new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM),
                start, end, null));
    }

    @SafeVarargs
    private static TushareRiskResponse response(
            String apiName,
            Map<String, Object>... rows
    ) {
        return new TushareRiskResponse(apiName, List.of(), List.of(rows));
    }

    private static Map<String, Object> marginRow(
            String exchange,
            String tradeDate,
            String financingBalance,
            String lendingBalance,
            String financingBuy,
            String financingRepay,
            String totalBalance
    ) {
        return Map.of(
                "exchange_id", exchange,
                "trade_date", tradeDate,
                "rzye", new BigDecimal(financingBalance),
                "rqye", new BigDecimal(lendingBalance),
                "rzmre", new BigDecimal(financingBuy),
                "rzche", new BigDecimal(financingRepay),
                "rzrqye", new BigDecimal(totalBalance));
    }

    private static Map<String, Object> zeroMarginRow(
            String exchange,
            String tradeDate
    ) {
        return marginRow(
                exchange, tradeDate,
                "0", "0", "0", "0", "0");
    }

    private static List<Map<String, Object>> zeroMarginDetailRows(
            String tradeDate,
            int count
    ) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            rows.add(marginDetailRow(
                    index % 2 == 0
                            ? "600519.SH" : "000001.SZ",
                    tradeDate,
                    "0", "0", "0", "0", "0"));
        }
        return rows;
    }

    private static Map<String, Object> fundBasicRow(
            String code,
            String name
    ) {
        return fundBasicRow(
                code, name, "20100101", "");
    }

    private static Map<String, Object> fundBasicRow(
            String code,
            String name,
            String listDate,
            String delistDate
    ) {
        return Map.of(
                "ts_code", code,
                "name", name,
                "fund_type", "股票型",
                "type", name.contains("ETF") ? "ETF" : "契约型开放式",
                "status", "L",
                "list_date", listDate,
                "delist_date", delistDate);
    }

    private static Map<String, Object> etfBasicRow(
            String code,
            String listStatus
    ) {
        return Map.of(
                "ts_code", code,
                "cname", "官方ETF目录条目",
                "list_status", listStatus,
                "exchange", code.endsWith(".SH")
                        ? "SSE" : "SZSE",
                "etf_type", "股票型");
    }

    private static Map<String, Object> tradeCalendarRow(
            String date,
            boolean open
    ) {
        return Map.of(
                "exchange", "SSE",
                "cal_date", date,
                "is_open", open ? "1" : "0");
    }

    private static TushareRiskResponse standardTradeCalendar() {
        return response("trade_cal",
                tradeCalendarRow("20260630", true),
                tradeCalendarRow("20260701", true),
                tradeCalendarRow("20260710", true),
                tradeCalendarRow("20260713", true),
                tradeCalendarRow("20260715", true),
                tradeCalendarRow("20260716", true),
                tradeCalendarRow("20260717", true),
                tradeCalendarRow("20260718", false),
                tradeCalendarRow("20260720", true));
    }

    private static Map<String, Object> marginDetailRow(
            String code,
            String tradeDate,
            String financingBalance,
            String financingBuy,
            String financingRepay,
            String lendingBalance,
            String totalBalance
    ) {
        return Map.of(
                "ts_code", code,
                "trade_date", tradeDate,
                "rzye", new BigDecimal(financingBalance),
                "rzmre", new BigDecimal(financingBuy),
                "rzche", new BigDecimal(financingRepay),
                "rqye", new BigDecimal(lendingBalance),
                "rzrqye", new BigDecimal(totalBalance));
    }

    private static Map<String, Object> holderTradeRow(
            String direction,
            String announcementDate,
            String beginDate,
            String closeDate,
            String volume,
            String ratio
    ) {
        return Map.ofEntries(
                Map.entry("ts_code", "600519.SH"),
                Map.entry("ann_date", announcementDate),
                Map.entry("holder_name", "示例股东"),
                Map.entry("holder_type", "P"),
                Map.entry("in_de", direction),
                Map.entry("change_vol", new BigDecimal(volume)),
                Map.entry("change_ratio", new BigDecimal(ratio)),
                Map.entry("after_share", new BigDecimal("100000")),
                Map.entry("after_ratio", new BigDecimal("1.5")),
                Map.entry("avg_price", new BigDecimal("100")),
                Map.entry("total_share", new BigDecimal("100000")),
                Map.entry("begin_date", beginDate),
                Map.entry("close_date", closeDate));
    }

    private static Map<String, Object> forecastRow(
            String code,
            String minimumChange,
            String maximumChange,
            String summary,
            String reason
    ) {
        return Map.ofEntries(
                Map.entry("ts_code", code),
                Map.entry("ann_date", "20260716"),
                Map.entry("end_date", "20260630"),
                Map.entry("type", "预减"),
                Map.entry("p_change_min", new BigDecimal(minimumChange)),
                Map.entry("p_change_max", new BigDecimal(maximumChange)),
                Map.entry("net_profit_min", new BigDecimal("1000")),
                Map.entry("net_profit_max", new BigDecimal("1200")),
                Map.entry("last_parent_net", new BigDecimal("900")),
                Map.entry("first_ann_date", "20260715"),
                Map.entry("summary", summary),
                Map.entry("change_reason", reason));
    }

    private static TushareRiskException failure(
            TushareRiskException.Category category,
            String apiName
    ) {
        try {
            Constructor<TushareRiskException> constructor =
                    TushareRiskException.class.getDeclaredConstructor(
                            TushareRiskException.Category.class,
                            String.class,
                            Integer.class,
                            String.class,
                            String.class,
                            Duration.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    category, apiName, null,
                    "raw response must not escape; token=test-token-not-secret",
                    null, null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
