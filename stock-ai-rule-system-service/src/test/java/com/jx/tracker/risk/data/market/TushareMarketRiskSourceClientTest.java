package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TushareMarketRiskSourceClientTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T12:00:00Z"), SHANGHAI);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 18);
    private static final RiskObjectKey MARKET =
            new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey STOCK =
            new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private static final RiskObjectKey OTHER_STOCK =
            new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ");

    @Test
    void stockBasicMapsOnlyTheCurrentSnapshotWithNormalizedStockCodes() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenReturn(response(
                "stock_basic",
                Map.of(
                        "ts_code", "600519.sh",
                        "name", "贵州茅台",
                        "list_date", "20010827")));
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.CN_A_STOCK_MASTER,
                request(List.of(MARKET), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.source()).isEqualTo(TushareMarketRiskSourceClient.SOURCE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            StockMasterPoint point = (StockMasterPoint) record;
            assertThat(point.object()).isEqualTo(STOCK);
            assertThat(point.tradeDate()).isEqualTo(TODAY);
            assertThat(point.name()).isEqualTo("贵州茅台");
            assertThat(point.listDate()).isEqualTo(LocalDate.of(2001, 8, 27));
            assertThat(point.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 20, 0));
            assertThat(point.availableAt()).isEqualTo(point.observedAt());
        });
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient).query(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(query -> {
            assertThat(query.apiName()).isEqualTo("stock_basic");
            assertThat(query.params()).containsEntry("list_status", "L");
            assertThat(query.fields()).isEqualTo("ts_code,name,list_date");
        });
    }

    @Test
    void stockBasicDoesNotBackdateTheCurrentSnapshot() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.CN_A_STOCK_MASTER,
                request(
                        List.of(MARKET),
                        LocalDate.of(2025, 7, 18),
                        LocalDate.of(2025, 7, 18),
                        LocalDate.of(2025, 7, 18)));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.failureReason()).contains("stock_basic", "current snapshot");
        verify(httpClient, never()).query(any());
    }

    @Test
    void indexMemberAllPreservesStrictEffectiveDatesAndNormalizesObjects() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenReturn(response(
                "index_member_all",
                Map.of(
                        "ts_code", "600519.sh",
                        "l1_code", "801120.SI",
                        "l1_name", "食品饮料",
                        "in_date", "20210701",
                        "out_date", "20261231"),
                Map.of(
                        "ts_code", "600519.SH",
                        "l1_code", "801780.SI",
                        "l1_name", "银行",
                        "in_date", "20100101",
                        "out_date", "20201231")));
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.SW1_MEMBERSHIP,
                request(
                        List.of(STOCK),
                        LocalDate.of(2021, 7, 1),
                        LocalDate.of(2026, 7, 1),
                        TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            IndustryExposure exposure = (IndustryExposure) record;
            assertThat(exposure.stock()).isEqualTo(STOCK);
            assertThat(exposure.sector()).isEqualTo(
                    new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801120"));
            assertThat(exposure.sectorName()).isEqualTo("食品饮料");
            assertThat(exposure.validFrom()).isEqualTo(LocalDate.of(2021, 7, 1));
            assertThat(exposure.validTo()).isEqualTo(LocalDate.of(2026, 12, 31));
            assertThat(exposure.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 20, 0));
            assertThat(exposure.availableAt()).isEqualTo(exposure.observedAt());
        });
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient).query(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(query -> {
            assertThat(query.apiName()).isEqualTo("index_member_all");
            assertThat(query.params()).containsEntry("ts_code", "600519.SH");
            assertThat(query.fields())
                    .isEqualTo("l1_code,l1_name,ts_code,in_date,out_date");
        });
    }

    @Test
    void marketDailyJoinsStockAndBroadMarketWithRealBenchmarkAndAuditedLeader() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000985.CSI" -> response("index_daily", dailyRow(
                        "000985.CSI", "5000.00", "5100.00", "2000"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> response(query.apiName());
            };
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(List.of(MARKET, OTHER_STOCK), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).hasSize(2).allSatisfy(record -> {
            MarketDailyPoint point = (MarketDailyPoint) record;
            assertThat(point.benchmarkClose()).isEqualByComparingTo("4000.00");
            assertThat(point.leaderClose()).isEqualByComparingTo("1500.00");
            assertThat(point.benchmarkDefinition())
                    .isEqualTo("CSI300:000300.SH:index_daily:close");
            assertThat(point.leaderDefinition())
                    .isEqualTo("SSE50:000016.SH:index_daily:close");
            assertThat(point.proxy()).isTrue();
            assertThat(point.observedAt()).isEqualTo(TODAY.atTime(15, 0));
            assertThat(point.availableAt()).isEqualTo(TODAY.atTime(18, 0));
        });
        assertThat(result.records()).anySatisfy(record -> {
            MarketDailyPoint point = (MarketDailyPoint) record;
            assertThat(point.object()).isEqualTo(MARKET);
            assertThat(point.open()).isEqualByComparingTo("5000.00");
            assertThat(point.close()).isEqualByComparingTo("5100.00");
            assertThat(point.volume()).isEqualByComparingTo("2000");
        }).anySatisfy(record -> {
            MarketDailyPoint point = (MarketDailyPoint) record;
            assertThat(point.object()).isEqualTo(OTHER_STOCK);
            assertThat(point.open()).isEqualByComparingTo("10.00");
            assertThat(point.close()).isEqualByComparingTo("11.00");
            assertThat(point.volume()).isEqualByComparingTo("1000");
        });
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(4)).query(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(TushareRiskRequest::apiName)
                .containsExactlyInAnyOrder(
                        "daily", "index_daily", "index_daily", "index_daily");
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(query -> assertThat(query.params())
                        .containsEntry("start_date", "20260718")
                        .containsEntry("end_date", "20260718"));
    }

    @Test
    void marketDailyReturnsInsufficientHistoryWhenTheBenchmarkJoinIsMissing() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                default -> response(query.apiName());
            };
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(List.of(OTHER_STOCK), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).isEmpty();
        assertThat(result.failureReason()).contains("index_daily", "000300.SH");
    }

    @Test
    void dailyBasicMapsDirectStockPeTtmWithoutInventingARiskFreeYield() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenReturn(response(
                "daily_basic",
                Map.of(
                        "ts_code", "600519.SH",
                        "trade_date", "20260718",
                        "pe_ttm", new BigDecimal("25.00"))));
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.VALUATION,
                request(List.of(STOCK), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            ValuationPoint point = (ValuationPoint) record;
            assertThat(point.object()).isEqualTo(STOCK);
            assertThat(point.peTtm()).isEqualByComparingTo("25.00");
            assertThat(point.earningsYield()).isEqualByComparingTo("0.0400000000");
            assertThat(point.riskFreeYield()).isNull();
            assertThat(point.proxy()).isFalse();
            assertThat(point.constituentCount()).isZero();
            assertThat(point.constituentUniversePointInTime()).isTrue();
            assertThat(point.scoringEligible()).isTrue();
            assertThat(point.calculationVersion()).isEqualTo("tushare-daily-basic-pe-ttm-v1");
            assertThat(point.availabilityPolicyVersion())
                    .isEqualTo("tushare-cn-daily-close-available-1800-v1");
            assertThat(point.observedAt()).isEqualTo(TODAY.atTime(15, 0));
            assertThat(point.availableAt()).isEqualTo(TODAY.atTime(18, 0));
        });
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient).query(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(query -> {
            assertThat(query.apiName()).isEqualTo("daily_basic");
            assertThat(query.params())
                    .containsEntry("ts_code", "600519.SH")
                    .containsEntry("start_date", "20260718")
                    .containsEntry("end_date", "20260718");
            assertThat(query.fields()).isEqualTo("ts_code,trade_date,pe_ttm");
        });
    }

    @Test
    void marketValuationFailsClosedWithoutPointInTimeConstituentAggregation() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.VALUATION,
                request(List.of(MARKET), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).isEmpty();
        assertThat(result.failureReason())
                .contains("daily_basic", "point-in-time constituent");
        verify(httpClient, never()).query(any());
    }

    @Test
    void breadthAuditsTheDailyCrossSectionButDoesNotFabricateHistoricalCounts() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenReturn(response(
                "daily",
                Map.of(
                        "ts_code", "600519.SH",
                        "trade_date", "20260718",
                        "close", new BigDecimal("1500"),
                        "pre_close", new BigDecimal("1490")),
                Map.of(
                        "ts_code", "000001.SZ",
                        "trade_date", "20260718",
                        "close", new BigDecimal("10"),
                        "pre_close", new BigDecimal("11"))));
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.BREADTH,
                request(List.of(MARKET), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).isEmpty();
        assertThat(result.failureReason())
                .contains("daily", "advancing=1", "declining=1")
                .containsIgnoringCase("newHigh")
                .containsIgnoringCase("moving-average");
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient).query(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(query -> {
            assertThat(query.apiName()).isEqualTo("daily");
            assertThat(query.params()).containsEntry("trade_date", "20260718");
            assertThat(query.fields()).isEqualTo("ts_code,trade_date,close,pre_close");
        });
    }

    @Test
    void crossMarketQueriesMultipleLeadingSeriesAndFailsClosedWithoutCorrelationHistory() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return response(
                    query.apiName(),
                    Map.of(
                            "ts_code", code,
                            "trade_date", "20260718",
                            "close", new BigDecimal("100")));
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.CROSS_MARKET,
                request(List.of(MARKET), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).isEmpty();
        assertThat(result.failureReason())
                .contains("index_global", "SPX", "IXIC", "HSI", "N225")
                .containsIgnoringCase("dynamic correlation");
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(5)).query(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .filteredOn(query -> query.apiName().equals("index_global"))
                .extracting(query -> query.params().get("ts_code"))
                .containsExactlyInAnyOrder("SPX", "IXIC", "HSI", "N225");
        assertThat(requestCaptor.getAllValues())
                .anySatisfy(query -> {
                    assertThat(query.apiName()).isEqualTo("index_daily");
                    assertThat(query.params()).containsEntry("ts_code", "000985.CSI");
                });
    }

    @ParameterizedTest
    @EnumSource(
            value = TushareRiskException.Category.class,
            names = {"PERMISSION", "REMOTE", "PARSE", "RATE_LIMIT"}
    )
    void structuredTushareFailuresBecomeAuditableUnavailableBatches(
        TushareRiskException.Category category
    ) {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        TushareRiskException failure = failure(category, "stock_basic");
        when(httpClient.query(any())).thenThrow(failure);
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.CN_A_STOCK_MASTER,
                request(List.of(MARKET), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(result.records()).isEmpty();
        assertThat(result.failureReason())
                .contains("stock_basic", category.name().toLowerCase())
                .doesNotContain("test-token-not-secret", "raw response");
    }

    private static RiskProviderRequest request(
            List<RiskObjectKey> objects,
            LocalDate startDate,
            LocalDate resultStartDate,
            LocalDate endDate
    ) {
        return new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM),
                startDate, resultStartDate, endDate, null);
    }

    @SafeVarargs
    private static TushareRiskResponse response(
            String apiName,
            Map<String, Object>... rows
    ) {
        return new TushareRiskResponse(apiName, List.of(), List.of(rows));
    }

    private static Map<String, Object> dailyRow(
            String code,
            String open,
            String close,
            String volume
    ) {
        return Map.of(
                "ts_code", code,
                "trade_date", "20260718",
                "open", new BigDecimal(open),
                "close", new BigDecimal(close),
                "vol", new BigDecimal(volume));
    }

    private static Map<String, Object> closeRow(String code, String close) {
        return Map.of(
                "ts_code", code,
                "trade_date", "20260718",
                "close", new BigDecimal(close));
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
                    category,
                    apiName,
                    null,
                    "raw response must not escape; token=test-token-not-secret",
                    null,
                    null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
