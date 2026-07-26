package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.stream.IntStream;

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
                default -> marketDailySupport(query);
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
        verify(httpClient, org.mockito.Mockito.times(6)).query(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .extracting(TushareRiskRequest::apiName)
                .containsExactlyInAnyOrder(
                        "trade_cal", "daily", "adj_factor",
                        "index_daily", "index_daily", "index_daily");
        assertThat(requestCaptor.getAllValues())
                .allSatisfy(query -> assertThat(query.params())
                        .containsEntry("start_date", "20260718")
                        .containsEntry("end_date", "20260718"));
    }

    @Test
    void marketDailyAdjustsStockPricesWithStrictCodeAndTradeDateFactorJoin() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "adj_factor:000001.SZ" -> response(
                        "adj_factor",
                        adjFactorRow("600519.SH", TODAY, "99"),
                        adjFactorRow("000001.SZ", TODAY.minusDays(1), "88"),
                        adjFactorRow("000001.SZ", TODAY, "2"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> marketDailySupport(query);
            };
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(List.of(OTHER_STOCK), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            MarketDailyPoint point = (MarketDailyPoint) record;
            assertThat(point.open()).isEqualByComparingTo("20.00");
            assertThat(point.close()).isEqualByComparingTo("22.00");
            assertThat(point.volume()).isEqualByComparingTo("1000");
        });
        ArgumentCaptor<TushareRiskRequest> requestCaptor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(5)).query(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
                .filteredOn(query -> query.apiName().equals("adj_factor"))
                .singleElement()
                .satisfies(query -> {
                    assertThat(query.params())
                            .containsEntry("ts_code", "000001.SZ")
                            .containsEntry("start_date", "20260718")
                            .containsEntry("end_date", "20260718");
                    assertThat(query.fields()).isEqualTo("ts_code,trade_date,adj_factor");
                });
    }

    @Test
    void marketDailyFailsClosedWhenAStockDailyRowHasNoAdjustmentFactor() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "adj_factor:000001.SZ" -> response("adj_factor");
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> marketDailySupport(query);
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
        assertThat(result.failureReason()).contains("adj_factor", "000001.SZ");
    }

    @Test
    void marketDailyFailsClosedWhenTradeCalendarDoesNotConfirmTheWindow() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "trade_cal:null" -> response("trade_cal");
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> marketDailySupport(query);
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
        assertThat(result.failureReason()).contains("trade_cal");
    }

    @Test
    void marketDailyAcceptsExactlyNinetyFivePercentOpenDayCoverage() {
        List<LocalDate> openDates = datesEndingToday(20);
        List<LocalDate> coveredDates = openDates.subList(0, 19);
        TushareRiskHttpClient httpClient = marketDailyWindowClient(
                openDates, coveredDates);
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(
                        List.of(OTHER_STOCK),
                        openDates.getFirst(),
                        openDates.getFirst(),
                        openDates.getLast()));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).hasSize(19);
    }

    @Test
    void marketDailyMarksTruncatedOpenDayCoveragePartialWithoutAdvancingCheckpoint() {
        List<LocalDate> openDates = datesEndingToday(20);
        List<LocalDate> coveredDates = openDates.subList(0, 18);
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                MarketDatasetCode.MARKET_DAILY.code(),
                "stock:000001.SZ",
                "cursor-18",
                TODAY.minusDays(1).atTime(18, 0));
        TushareRiskHttpClient httpClient = marketDailyWindowClient(
                openDates, coveredDates);
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                new RiskProviderRequest(
                        List.of(OTHER_STOCK),
                        List.of(RiskHorizon.SHORT_TERM),
                        openDates.getFirst(),
                        openDates.getFirst(),
                        openDates.getLast(),
                        checkpoint));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).hasSize(18);
        assertThat(result.nextCheckpoint()).isNull();
        assertThat(result.failureReason()).contains("coverage", "90");
    }

    @Test
    void marketDailyFiltersMismatchedTargetRowsBeforeMappingTheRequestedStock() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response(
                        "daily",
                        dailyRow("600519.SH", "900.00", "999.00", "9999"),
                        dailyRow("000001.SZ", "10.00", "11.00", "1000"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> marketDailySupport(query);
            };
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(List.of(OTHER_STOCK), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            MarketDailyPoint point = (MarketDailyPoint) record;
            assertThat(point.object()).isEqualTo(OTHER_STOCK);
            assertThat(point.open()).isEqualByComparingTo("10.00");
            assertThat(point.close()).isEqualByComparingTo("11.00");
            assertThat(point.volume()).isEqualByComparingTo("1000");
        });
    }

    @Test
    void marketDailyRejectsARequestedStockResponseContainingOnlyAnotherCode() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "600519.SH", "900.00", "999.00", "9999"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> marketDailySupport(query);
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
    }

    @Test
    void marketDailyFiltersMismatchedBenchmarkRowsBeforeTradeDateJoin() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response(
                        "index_daily",
                        closeRow("399300.SZ", "9999.00"),
                        closeRow("000300.SH", "4000.00"));
                default -> marketDailySupport(query);
            };
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(List.of(OTHER_STOCK), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).singleElement().satisfies(record -> {
            MarketDailyPoint point = (MarketDailyPoint) record;
            assertThat(point.benchmarkClose()).isEqualByComparingTo("4000.00");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"000300.SH", "000016.SH"})
    void marketDailyRejectsBenchmarkOrLeaderResponsesContainingOnlyAnotherCode(
            String mismatchedSeriesCode
    ) {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            if (mismatchedSeriesCode.equals(code)) {
                return response("index_daily", closeRow("399999.SZ", "9999.00"));
            }
            return switch (query.apiName() + ":" + code) {
                case "daily:000001.SZ" -> response("daily", dailyRow(
                        "000001.SZ", "10.00", "11.00", "1000"));
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                default -> marketDailySupport(query);
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
    }

    @Test
    void marketDailyRejectsBroadMarketRowsWithAnotherIndexCode() {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "index_daily:000016.SH" -> response("index_daily", closeRow(
                        "000016.SH", "1500.00"));
                case "index_daily:000300.SH" -> response("index_daily", closeRow(
                        "000300.SH", "4000.00"));
                case "index_daily:000985.CSI" -> response("index_daily", dailyRow(
                        "000300.SH", "5000.00", "5100.00", "2000"));
                default -> marketDailySupport(query);
            };
        });
        TushareMarketRiskSourceClient client =
                new TushareMarketRiskSourceClient(httpClient, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY,
                request(List.of(MARKET), TODAY, TODAY, TODAY));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).isEmpty();
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
                default -> marketDailySupport(query);
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

    private static TushareRiskHttpClient marketDailyWindowClient(
            List<LocalDate> openDates,
            List<LocalDate> coveredDates
    ) {
        TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest query = invocation.getArgument(0);
            String code = (String) query.params().get("ts_code");
            return switch (query.apiName() + ":" + code) {
                case "trade_cal:null" -> responseRows(
                        "trade_cal",
                        openDates.stream().map(TushareMarketRiskSourceClientTest::openDayRow)
                                .toList());
                case "daily:000001.SZ" -> responseRows(
                        "daily",
                        coveredDates.stream().map(date -> dailyRow(
                                "000001.SZ", date, "10.00", "11.00", "1000"))
                                .toList());
                case "adj_factor:000001.SZ" -> responseRows(
                        "adj_factor",
                        coveredDates.stream().map(date ->
                                adjFactorRow("000001.SZ", date, "1"))
                                .toList());
                case "index_daily:000016.SH" -> responseRows(
                        "index_daily",
                        openDates.stream().map(date ->
                                closeRow("000016.SH", date, "1500.00"))
                                .toList());
                case "index_daily:000300.SH" -> responseRows(
                        "index_daily",
                        openDates.stream().map(date ->
                                closeRow("000300.SH", date, "4000.00"))
                                .toList());
                default -> response(query.apiName());
            };
        });
        return httpClient;
    }

    private static TushareRiskResponse marketDailySupport(TushareRiskRequest query) {
        if ("trade_cal".equals(query.apiName())) {
            return response("trade_cal", openDayRow(TODAY));
        }
        if ("adj_factor".equals(query.apiName())) {
            String code = (String) query.params().get("ts_code");
            return response("adj_factor", adjFactorRow(code, TODAY, "1"));
        }
        return response(query.apiName());
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

    private static TushareRiskResponse responseRows(
            String apiName,
            List<Map<String, Object>> rows
    ) {
        return new TushareRiskResponse(apiName, List.of(), rows);
    }

    private static Map<String, Object> dailyRow(
            String code,
            String open,
            String close,
            String volume
    ) {
        return dailyRow(code, TODAY, open, close, volume);
    }

    private static Map<String, Object> dailyRow(
            String code,
            LocalDate tradeDate,
            String open,
            String close,
            String volume
    ) {
        return Map.of(
                "ts_code", code,
                "trade_date", compact(tradeDate),
                "open", new BigDecimal(open),
                "close", new BigDecimal(close),
                "vol", new BigDecimal(volume));
    }

    private static Map<String, Object> closeRow(String code, String close) {
        return closeRow(code, TODAY, close);
    }

    private static Map<String, Object> closeRow(
            String code,
            LocalDate tradeDate,
            String close
    ) {
        return Map.of(
                "ts_code", code,
                "trade_date", compact(tradeDate),
                "close", new BigDecimal(close));
    }

    private static Map<String, Object> adjFactorRow(
            String code,
            LocalDate tradeDate,
            String factor
    ) {
        return Map.of(
                "ts_code", code,
                "trade_date", compact(tradeDate),
                "adj_factor", new BigDecimal(factor));
    }

    private static Map<String, Object> openDayRow(LocalDate date) {
        return Map.of(
                "exchange", "SSE",
                "cal_date", compact(date),
                "is_open", "1");
    }

    private static List<LocalDate> datesEndingToday(int size) {
        return IntStream.range(0, size)
                .mapToObj(index -> TODAY.minusDays(size - index - 1L))
                .toList();
    }

    private static String compact(LocalDate date) {
        return date.toString().replace("-", "");
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
