package com.jx.tracker.risk.data.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.data.event.EventEconomicMeaningDictionary;
import com.jx.tracker.risk.data.event.FlowEventTranslation;
import com.jx.tracker.risk.data.event.FlowEventTranslator;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AkToolsFlowEventSourceClientTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private static final RiskObjectKey OTHER_STOCK = new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ");

    @Test
    void mapsActualMarginFieldsToMarketAndBuildsConservativePointInTime() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_margin_account_info"))
                .andRespond(withSuccess("""
                        [
                          {"日期":"2026-07-16","融资余额":120.0,"融券余额":3.0},
                          {"日期":"2026-07-17","融资余额":108.0,"融券余额":2.8}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.MARGIN_FINANCING, List.of(MARKET),
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).hasSize(2).allSatisfy(record ->
                assertThat(record.object()).isEqualTo(MARKET));
        assertThat(batch.records().get(1).value()).isEqualByComparingTo("108.0");
        assertThat(batch.records().get(1).attributes()).containsEntry("previousBalance", new java.math.BigDecimal("120.0"));
        assertThat(batch.records().get(1).tradeDate()).isEqualTo(LocalDate.of(2026, 7, 17));
        assertThat(batch.records().get(1).availableAt())
                .isEqualTo(LocalDateTime.of(2026, 7, 18, 0, 0));
        server.verify();
    }

    @Test
    void missingRequiredNumericFieldIsUnavailableInsteadOfZero() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_margin_account_info"))
                .andRespond(withSuccess("[{\"日期\":\"2026-07-17\",\"融券余额\":2.8}]", MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.MARGIN_FINANCING, List.of(MARKET),
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(batch.failureReason()).contains("融资余额").contains("missing");
        server.verify();
    }

    @Test
    void invalidNumericFieldIsUnavailableAndOutOfWindowNumericRowsAreInsufficient() {
        RestClient.Builder invalidBuilder = RestClient.builder();
        MockRestServiceServer invalidServer = MockRestServiceServer.bindTo(invalidBuilder).build();
        invalidServer.expect(requestTo("http://127.0.0.1:8090/api/public/stock_margin_account_info"))
                .andRespond(withSuccess("[{\"日期\":\"2026-07-17\",\"融资余额\":\"--\"}]",
                        MediaType.APPLICATION_JSON));

        assertThat(client(invalidBuilder).fetch(sourceRequest(
                FlowEventDataset.MARGIN_FINANCING, List.of(MARKET),
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18))).qualityStatus())
                .isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        invalidServer.verify();

        RestClient.Builder historyBuilder = RestClient.builder();
        MockRestServiceServer historyServer = MockRestServiceServer.bindTo(historyBuilder).build();
        historyServer.expect(requestTo("http://127.0.0.1:8090/api/public/stock_margin_account_info"))
                .andRespond(withSuccess("[{\"日期\":\"2025-07-17\",\"融资余额\":120}]",
                        MediaType.APPLICATION_JSON));

        FlowEventSourceBatch history = client(historyBuilder).fetch(sourceRequest(
                FlowEventDataset.MARGIN_FINANCING, List.of(MARKET),
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)));
        assertThat(history.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        historyServer.verify();
    }

    @Test
    void mapsActualEtfFieldsToMarketAndKeepsZeroReferenceForInsufficientHandling() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/fund_etf_spot_em"))
                .andRespond(withSuccess("""
                        [
                          {"代码":"510300","主力净流入-净额":-2000000,"流通市值":100000000,
                           "数据日期":"2026-07-17","更新时间":"15:01:00"},
                          {"代码":"510500","主力净流入-净额":0,"流通市值":0,
                           "数据日期":"2026-07-17","更新时间":"15:02:00"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET),
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.object()).isEqualTo(MARKET);
            assertThat(record.value()).isEqualByComparingTo("-2000000");
            assertThat(record.attributes()).containsEntry("referenceAssets", new java.math.BigDecimal("100000000"));
            assertThat(record.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 17, 15, 2));
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 0, 0));
        });
        server.verify();
    }

    @Test
    void acceptsOfficialAndIsoOffsetDateTimesForEtfUpdateTime() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/fund_etf_spot_em"))
                .andRespond(withSuccess("""
                        [
                          {"代码":"510300","主力净流入-净额":-1000000,"流通市值":50000000,
                           "数据日期":"2026-07-17","更新时间":"2026-07-17 15:01:00+08:00"},
                          {"代码":"510500","主力净流入-净额":-2000000,"流通市值":80000000,
                           "数据日期":"2026-07-17","更新时间":"2026-07-17T15:02:00+08:00"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET),
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.value()).isEqualByComparingTo("-3000000");
            assertThat(record.attributes()).containsEntry(
                    "referenceAssets", new java.math.BigDecimal("130000000"));
            assertThat(record.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 17, 15, 2));
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 0, 0));
        });
        server.verify();
    }

    @Test
    void filtersFullTableForecastToRequestedStockAndPreservesImprovementDirection() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20251231"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260331"))
                .andRespond(withSuccess("""
                        [
                          {"股票代码":"600519","股票简称":"贵州茅台","业绩变动幅度":18.5,"预告类型":"预增","公告日期":"2026-04-10"},
                          {"股票代码":"000001","股票简称":"平安银行","业绩变动幅度":-22.0,"预告类型":"预减","公告日期":"2026-04-11"},
                          {"股票代码":"200429","股票简称":"粤高速B","业绩变动幅度":-12.0,"预告类型":"预减","公告日期":"2026-04-11"},
                          {"股票代码":"900901","股票简称":"云赛B股","业绩变动幅度":-8.0,"预告类型":"预减","公告日期":"2026-04-11"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260630"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 30)));

        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.object()).isEqualTo(STOCK);
            assertThat(record.value()).isEqualByComparingTo("18.5");
            assertThat(record.attributes()).containsEntry("adverse", false);
            assertThat(record.observedAt()).isEqualTo(LocalDateTime.of(2026, 4, 10, 0, 0));
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 4, 11, 0, 0));
        });
        server.verify();
    }

    @Test
    void missingReliableAnnouncementDateIsInsufficientHistory() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andRespond(withSuccess("[{\"代码\":\"600519\",\"公告标题\":\"风险提示公告\"}]",
                        MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.STOCK_ANNOUNCEMENT, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(batch.failureReason()).contains("公告时间").contains("point-in-time");
        server.verify();
    }

    @Test
    void mapsReductionDirectionAndFiltersFullTableToRequestedStock() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_ggcg_em"))
                .andRespond(withSuccess("""
                        [
                          {"代码":"600519","持股变动信息-增减":"增持","持股变动信息-变动数量":10000,
                           "变动开始日":"2026-07-10","变动截止日":"2026-07-16","公告日":"2026-07-17"},
                          {"代码":"000001","持股变动信息-增减":"减持","持股变动信息-变动数量":20000,
                           "变动开始日":"2026-07-11","变动截止日":"2026-07-16","公告日":"2026-07-17"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.SHARE_REDUCTION, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));

        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.object()).isEqualTo(STOCK);
            assertThat(record.value()).isEqualByComparingTo("10000");
            assertThat(record.attributes())
                    .containsEntry("actualReduction", false)
                    .containsEntry("adverse", false);
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 16));
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 0, 0));
        });
        server.verify();
    }

    @Test
    void mapsActualAnnouncementAndUnlockFieldsWithIndependentAvailability() {
        RestClient.Builder announcementBuilder = RestClient.builder();
        MockRestServiceServer announcementServer = MockRestServiceServer.bindTo(announcementBuilder).build();
        announcementServer.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andRespond(withSuccess("""
                        [{"代码":"600519","简称":"贵州茅台","公告标题":"风险提示公告",
                          "公告时间":"2026-07-17 18:30:00","公告链接":"https://example.invalid/1"}]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch announcement = client(announcementBuilder).fetch(sourceRequest(
                FlowEventDataset.STOCK_ANNOUNCEMENT, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));

        assertThat(announcement.records()).singleElement().satisfies(record -> {
            assertThat(record.object()).isEqualTo(STOCK);
            assertThat(record.value()).isNull();
            assertThat(record.attributes()).containsEntry("economicMeaning", "market_trust");
            assertThat(record.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 17, 18, 30));
            assertThat(record.availableAt()).isEqualTo(record.observedAt());
        });
        announcementServer.verify();

        RestClient.Builder unlockBuilder = RestClient.builder();
        MockRestServiceServer unlockServer = MockRestServiceServer.bindTo(unlockBuilder).build();
        unlockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_restricted_release_queue_sina")))
                .andRespond(withSuccess("""
                        [{"代码":"600519","名称":"贵州茅台","解禁日期":"2026-08-01",
                          "解禁数量":"1,000,000","解禁股流通市值":500000000,"上市批次":1,
                          "公告日期":"2026-07-17"}]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch unlock = client(unlockBuilder).fetch(sourceRequest(
                FlowEventDataset.SHARE_UNLOCK, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 2)));

        assertThat(unlock.records()).singleElement().satisfies(record -> {
            assertThat(record.object()).isEqualTo(STOCK);
            assertThat(record.value()).isEqualByComparingTo("1000000");
            assertThat(record.attributes()).containsEntry("scheduled", true);
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 0, 0));
        });
        unlockServer.verify();
    }

    @Test
    void fansOutMultiStockAnnouncementRequestsAndMergesInStableOrder() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andExpect(queryParam("symbol", "000001"))
                .andRespond(withSuccess("""
                        [{"代码":"000001","公告标题":"诉讼风险提示", "公告时间":"2026-07-16 18:00:00"}]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andExpect(queryParam("symbol", "600519"))
                .andRespond(withSuccess("""
                        [{"代码":"600519","公告标题":"融资风险提示", "公告时间":"2026-07-17 18:30:00"}]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.STOCK_ANNOUNCEMENT, List.of(STOCK, OTHER_STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).extracting(FlowEventSourceRecord::object)
                .containsExactly(OTHER_STOCK, STOCK);
        server.verify();
    }

    @Test
    void fansOutMultiStockUnlockRequestsAndDeduplicatesEachResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String otherUnlock = """
                {"代码":"000001","解禁日期":"2026-08-01","解禁数量":1000,"公告日期":"2026-07-17"}
                """;
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_restricted_release_queue_sina")))
                .andExpect(queryParam("symbol", "000001"))
                .andRespond(withSuccess("[" + otherUnlock + "," + otherUnlock + "]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_restricted_release_queue_sina")))
                .andExpect(queryParam("symbol", "600519"))
                .andRespond(withSuccess("""
                        [{"代码":"600519","解禁日期":"2026-08-02","解禁数量":2000,
                          "公告日期":"2026-07-17"}]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.SHARE_UNLOCK, List.of(STOCK, OTHER_STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 3)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).extracting(FlowEventSourceRecord::object)
                .containsExactly(OTHER_STOCK, STOCK);
        server.verify();
    }

    @Test
    void emptySuccessfulEventResponseIsValidZero() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andRespond(withSuccess("{\"data\":[],\"meta\":{\"nextCursor\":\"end\"}}",
                        MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.STOCK_ANNOUNCEMENT, List.of(STOCK),
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(batch.nextCursor()).isEqualTo("end");
        server.verify();
    }

    @Test
    void emptyRecentOnlyUnlockResponseIsInsufficientAndHasNoCursor() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_restricted_release_queue_sina")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.SHARE_UNLOCK, List.of(STOCK),
                LocalDate.of(2021, 7, 18), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(batch.failureReason()).contains("history");
        assertThat(batch.nextCursor()).isNull();
        server.verify();
    }

    @Test
    void earningsForecastBackfillQueriesEveryCompletedQuarterInRange() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20251231"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260331"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260630"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260930"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        server.verify();
    }

    @Test
    void shortForecastWindowQueriesPreviousReportPeriodAndFiltersByAvailability() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260630"))
                .andRespond(withSuccess("""
                        [
                          {"股票代码":"600519","业绩变动幅度":-12.0,"预告类型":"预减","公告日期":"2026-07-08"},
                          {"股票代码":"600519","业绩变动幅度":-20.0,"预告类型":"预减","公告日期":"2026-07-12"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260930"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK),
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.value()).isEqualByComparingTo("-20.0");
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 13, 0, 0));
        });
        server.verify();
    }

    @Test
    void forecastWindowQueriesItsUnfinishedReportQuarterAndFiltersByAvailability() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20251231"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260331"))
                .andRespond(withSuccess("""
                        [
                          {"股票代码":"600519","业绩变动幅度":-8.0,"预告类型":"预减","公告日期":"2025-12-28"},
                          {"股票代码":"600519","业绩变动幅度":-18.0,"预告类型":"预减","公告日期":"2026-02-10"},
                          {"股票代码":"600519","业绩变动幅度":-28.0,"预告类型":"预减","公告日期":"2026-03-15"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 15)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.value()).isEqualByComparingTo("-18.0");
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 2, 11, 0, 0));
        });
        server.verify();
    }

    @Test
    void etfSpotIsAnExplicitCurrentOrderFlowProxyAndCannotSatisfyFiveYearHistory() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/fund_etf_spot_em"))
                .andRespond(withSuccess("""
                        [{"代码":"510300","主力净流入-净额":-2000000,"流通市值":100000000,
                          "数据日期":"2026-07-18","更新时间":"2026-07-18T15:01:00+08:00"}]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET),
                LocalDate.of(2021, 7, 18), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.historyComplete()).isFalse();
        assertThat(batch.failureReason()).contains("current order-flow proxy");
        server.verify();
    }

    @Test
    void currentEtfSpotRecordIsMarkedAsProxyAndNeverAsRedemptionFact() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/fund_etf_spot_em"))
                .andRespond(withSuccess("""
                        [{"代码":"510300","主力净流入-净额":-2000000,"流通市值":100000000,
                          "数据日期":"2026-07-18","更新时间":"2026-07-18T15:01:00+08:00"}]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET),
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.historyComplete()).isFalse();
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.eventCode()).isEqualTo("etf_order_flow_proxy");
            assertThat(record.title()).contains("代理");
            assertThat(record.attributes())
                    .containsEntry("proxy", true)
                    .containsEntry("proxyType", "secondaryMarketOrderFlow")
                    .doesNotContainKey("redemptionFact");
        });
        server.verify();
    }

    @Test
    void collidingAnnouncementTitleHashesStillProduceDistinctSourceIds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andRespond(withSuccess("""
                        [
                          {"代码":"600519","公告标题":"Aa风险提示","公告时间":"2026-07-17 18:30:00"},
                          {"代码":"600519","公告标题":"BB风险提示","公告时间":"2026-07-17 18:30:00"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.STOCK_ANNOUNCEMENT, List.of(STOCK),
                LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)));

        assertThat("Aa风险提示".hashCode()).isEqualTo("BB风险提示".hashCode());
        assertThat(batch.records()).extracting(FlowEventSourceRecord::recordId)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allMatch(id -> id.matches("stock_announcement:600519\\.SH:sha256:[0-9a-f]{64}"));
        server.verify();
    }

    @Test
    void historicalEtfFlowUsesDerivedGatewayAndPreservesPointInTimeMetadata() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:18090/api/risk/etf-redemption")))
                .andExpect(queryParam("start_date", "20210718"))
                .andExpect(queryParam("end_date", "20260718"))
                .andExpect(queryParam("objects", "market:CN-A"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"tradeDate":"2026-07-17","netFlow":-3000000,
                           "referenceAssets":150000000,
                           "observedAt":"2026-07-17T15:10:00+08:00",
                           "availableAt":"2026-07-17T16:00:00+08:00"}
                        ],"meta":{"historyComplete":true,"earliestAvailableDate":"2021-07-18"}}
                        """, MediaType.APPLICATION_JSON));
        AkToolsFlowEventSourceClient client = new AkToolsFlowEventSourceClient(
                "http://127.0.0.1:8090",
                "http://127.0.0.1:18090",
                builder,
                new ObjectMapper(),
                fixedClock());

        FlowEventSourceBatch batch = client.fetch(sourceRequest(
                FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET),
                LocalDate.of(2021, 7, 18), LocalDate.of(2026, 7, 18)));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.historyComplete()).isTrue();
        assertThat(batch.earliestAvailableDate()).isEqualTo(LocalDate.of(2021, 7, 18));
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.eventCode()).isEqualTo("etf_redemption_flow");
            assertThat(record.attributes())
                    .containsEntry("referenceAssets", new java.math.BigDecimal("150000000"))
                    .doesNotContainKey("proxy");
            assertThat(record.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 17, 15, 10));
            assertThat(record.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 17, 16, 0));
        });
        server.verify();
    }

    @Test
    void rejectsDerivedEtfHistoryWhenEarliestDateIsMissingOrLaterThanRequestedStart() {
        for (String meta : List.of(
                "{\"historyComplete\":true}",
                "{\"historyComplete\":true,\"earliestAvailableDate\":\"2022-07-18\"}"
        )) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                            "http://127.0.0.1:18090/api/risk/etf-redemption")))
                    .andRespond(withSuccess("""
                            {"data":[
                              {"tradeDate":"2026-07-17","netFlow":-3000000,
                               "referenceAssets":150000000,
                               "observedAt":"2026-07-17T15:10:00+08:00",
                               "availableAt":"2026-07-17T16:00:00+08:00"}
                            ],"meta":%s}
                            """.formatted(meta), MediaType.APPLICATION_JSON));
            AkToolsFlowEventSourceClient client = new AkToolsFlowEventSourceClient(
                    "http://127.0.0.1:8090",
                    "http://127.0.0.1:18090",
                    builder,
                    new ObjectMapper(),
                    fixedClock());

            FlowEventSourceBatch batch = client.fetch(sourceRequest(
                    FlowEventDataset.ETF_FUND_FLOW, List.of(MARKET),
                    LocalDate.of(2021, 7, 18), LocalDate.of(2026, 7, 18)));

            assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
            assertThat(batch.historyComplete()).isFalse();
            assertThat(batch.records()).hasSize(1);
            assertThat(batch.nextCursor()).isNull();
            assertThat(batch.failureReason()).contains("earliestAvailableDate");
            server.verify();
        }
    }

    @Test
    void unlockKeepsTenThousandShareUnitAndDistinctListingBatchesWithoutInventingSeverity() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_restricted_release_queue_sina")))
                .andRespond(withSuccess("""
                        [
                          {"代码":"600519","解禁日期":"2026-08-01","解禁数量":1000,
                           "解禁股流通市值":50000,"上市批次":1,"公告日期":"2026-07-17"},
                          {"代码":"600519","解禁日期":"2026-08-01","解禁数量":2000,
                           "解禁股流通市值":90000,"上市批次":2,"公告日期":"2026-07-17"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.SHARE_UNLOCK, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 2)));

        assertThat(batch.records()).hasSize(2);
        assertThat(batch.records()).extracting(FlowEventSourceRecord::recordId)
                .doesNotHaveDuplicates();
        assertThat(batch.records()).allSatisfy(record ->
                assertThat(record.unit()).isEqualTo("tenThousandShares"));
        FlowEventTranslation translation = translator().translate(
                FlowEventDataset.SHARE_UNLOCK, batch.records().getFirst(),
                RiskHorizon.SHORT_TERM, batch.source(), null);
        assertThat(translation.events()).singleElement().satisfies(event -> {
            assertThat(event.severityScore()).isNull();
            assertThat(event.payload()).doesNotContainKey("modifierSeverity");
        });
        server.verify();
    }

    @Test
    void reductionUsesPublishedRatioForModifierStrengthAndKeepsDistinctShareholders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_ggcg_em"))
                .andRespond(withSuccess("""
                        [
                          {"代码":"600519","股东名称":"股东甲","持股变动信息-增减":"减持",
                           "持股变动信息-变动数量":20000,"持股变动信息-占流通股比例":0.12,
                           "变动开始日":"2026-07-10","变动截止日":"2026-07-16","公告日":"2026-07-17"},
                          {"代码":"600519","股东名称":"股东乙","持股变动信息-增减":"减持",
                           "持股变动信息-变动数量":30000,"持股变动信息-占流通股比例":0.18,
                           "变动开始日":"2026-07-11","变动截止日":"2026-07-16","公告日":"2026-07-17"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.SHARE_REDUCTION, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));

        assertThat(batch.records()).hasSize(2);
        assertThat(batch.records()).extracting(FlowEventSourceRecord::recordId)
                .doesNotHaveDuplicates();
        assertThat(batch.records()).allSatisfy(record ->
                assertThat(record.unit()).isEqualTo("tenThousandShares"));
        FlowEventTranslation translation = translator().translate(
                FlowEventDataset.SHARE_REDUCTION, batch.records().getFirst(),
                RiskHorizon.SHORT_TERM, batch.source(), null);
        assertThat(translation.events()).singleElement().satisfies(event -> {
            assertThat(event.severityScore()).isNull();
            assertThat(event.payload()).containsEntry("modifierSeverity", new java.math.BigDecimal("0.12"));
        });
        server.verify();
    }

    @Test
    void forecastIdentityIncludesPredictionMetricForSameDayRows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260630"))
                .andRespond(withSuccess("""
                        [
                          {"股票代码":"600519","预测指标":"净利润","业绩变动幅度":-12,
                           "预告类型":"预减","公告日期":"2026-07-12"},
                          {"股票代码":"600519","预测指标":"扣非净利润","业绩变动幅度":-18,
                           "预告类型":"预减","公告日期":"2026-07-12"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260930"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FlowEventSourceBatch batch = client(builder).fetch(sourceRequest(
                FlowEventDataset.EARNINGS_FORECAST, List.of(STOCK),
                LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 18)));

        assertThat(batch.records()).hasSize(2);
        assertThat(batch.records()).extracting(FlowEventSourceRecord::recordId)
                .doesNotHaveDuplicates();
        assertThat(batch.records()).extracting(record -> record.attributes().get("predictionMetric"))
                .containsExactlyInAnyOrder("净利润", "扣非净利润");
        server.verify();
    }

    @Test
    void fullMarketReductionResponseIsSharedAcrossStockChunks() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_ggcg_em"))
                .andRespond(withSuccess("""
                        [
                          {"代码":"600519","股东名称":"股东甲","持股变动信息-增减":"减持",
                           "持股变动信息-变动数量":200,"持股变动信息-占流通股比例":0.12,
                           "变动截止日":"2026-07-16","公告日":"2026-07-17"},
                          {"代码":"000001","股东名称":"股东乙","持股变动信息-增减":"减持",
                           "持股变动信息-变动数量":300,"持股变动信息-占流通股比例":0.18,
                           "变动截止日":"2026-07-16","公告日":"2026-07-17"}
                        ]
                        """, MediaType.APPLICATION_JSON));
        AkToolsFlowEventSourceClient client = client(builder);

        FlowEventSourceBatch first = client.fetch(sourceRequest(
                FlowEventDataset.SHARE_REDUCTION, List.of(STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));
        FlowEventSourceBatch second = client.fetch(sourceRequest(
                FlowEventDataset.SHARE_REDUCTION, List.of(OTHER_STOCK),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18)));

        assertThat(first.records()).extracting(FlowEventSourceRecord::object).containsExactly(STOCK);
        assertThat(second.records()).extracting(FlowEventSourceRecord::object).containsExactly(OTHER_STOCK);
        server.verify();
    }

    private FlowEventTranslator translator() {
        return new FlowEventTranslator(EventEconomicMeaningDictionary.defaultDictionary());
    }

    private AkToolsFlowEventSourceClient client(RestClient.Builder builder) {
        return new AkToolsFlowEventSourceClient(
                "http://127.0.0.1:8090", builder, new ObjectMapper(), fixedClock());
    }

    private FlowEventSourceRequest sourceRequest(
            FlowEventDataset dataset,
            List<RiskObjectKey> objects,
            LocalDate start,
            LocalDate end
    ) {
        return new FlowEventSourceRequest(dataset, new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM), start, end, null));
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-18T09:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
