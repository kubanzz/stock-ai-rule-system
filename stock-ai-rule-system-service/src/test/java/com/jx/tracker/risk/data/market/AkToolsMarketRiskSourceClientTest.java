package com.jx.tracker.risk.data.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AkToolsMarketRiskSourceClientTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC);
    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");

    @Test
    void stockMasterCallsTheNoArgumentAkShareEndpointAndUsesAcquisitionTime() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.stockMaster = List.of(Map.of("code", "600519", "name", "贵州茅台"));
        AkToolsMarketRiskSourceClient client = new AkToolsMarketRiskSourceClient(transport, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.CN_A_STOCK_MASTER, currentRequest(List.of(STOCK)));

        assertThat(transport.calls).containsExactly(
                new Call("/api/public/stock_info_a_code_name", Map.of()));
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record).isInstanceOf(StockMasterPoint.class);
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 18));
            assertThat(record.object()).isEqualTo(STOCK);
            assertThat(record.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0));
            assertThat(record.availableAt()).isEqualTo(record.observedAt());
        });
    }

    @Test
    void historicalStockMasterDoesNotBackdateTheCurrentSnapshot() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.stockMaster = List.of(Map.of("code", "600519", "name", "贵州茅台"));
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(transport, CLOCK));

        RiskProviderBatch result = provider.fetch(
                MarketDatasetCode.CN_A_STOCK_MASTER.code(), historicalRequest(List.of(STOCK)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.errorMessage()).contains("current snapshot");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void currentSw1MembershipUsesOnlyOfficialSignaturesAndInjectsTheRequestedSector() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.sw1Catalog = List.of(Map.of("行业代码", "801780.SI", "行业名称", "银行"));
        transport.sw1Components = List.of(Map.of(
                "序号", 1,
                "证券代码", "600519",
                "证券名称", "贵州茅台",
                "最新权重", "1.23",
                "计入日期", "2024-07-01"
        ));
        AkToolsMarketRiskSourceClient client = new AkToolsMarketRiskSourceClient(transport, CLOCK);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.SW1_MEMBERSHIP, snapshotRequest(List.of(STOCK)));

        assertThat(transport.calls).containsExactly(
                new Call("/api/public/sw_index_first_info", Map.of()),
                new Call("/api/public/index_component_sw", Map.of("symbol", "801780")));
        assertThat(result.records()).singleElement().satisfies(record -> {
            IndustryExposure exposure = (IndustryExposure) record;
            assertThat(exposure.stock()).isEqualTo(STOCK);
            assertThat(exposure.sector()).isEqualTo(
                    new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780"));
            assertThat(exposure.validFrom()).isEqualTo(LocalDate.of(2024, 7, 1));
            assertThat(exposure.observedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0));
            assertThat(exposure.availableAt()).isEqualTo(exposure.observedAt());
        });
    }

    @Test
    void dailyFiveYearRequestPersistsCurrentSw1ExposureButKeepsHistoricalCoverageInsufficient() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.sw1Catalog = List.of(Map.of("行业代码", "801780.SI", "行业名称", "银行"));
        transport.sw1Components = List.of(Map.of(
                "证券代码", "600519",
                "证券名称", "贵州茅台",
                "计入日期", "2024-07-01"
        ));
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(transport, CLOCK));

        RiskProviderBatch result = provider.fetch(
                MarketDatasetCode.SW1_MEMBERSHIP.code(), currentRequest(List.of(STOCK)));

        assertThat(transport.calls).containsExactly(
                new Call("/api/public/sw_index_first_info", Map.of()),
                new Call("/api/public/index_component_sw", Map.of("symbol", "801780")));
        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.errorMessage()).contains("current snapshot", "historical");
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.tradeDate()).isEqualTo(LocalDate.of(2024, 7, 1));
            assertThat(observation.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0));
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(observation.value()).isNull();
            assertThat(observation.attributes())
                    .containsEntry("auditValue", java.math.BigDecimal.ONE)
                    .containsEntry("sourceQuality", "available");
        });
        assertThat(result.industryExposures()).singleElement().satisfies(exposure -> {
            assertThat(exposure.validFrom()).isEqualTo(LocalDate.of(2024, 7, 1));
            assertThat(exposure.validTo()).isNull();
            assertThat(exposure.availableAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0));
            assertThat(exposure.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        });
        assertThat(result.observations()).allMatch(observation ->
                observation.availableAt().isAfter(LocalDateTime.of(2026, 7, 17, 23, 59, 59)));
    }

    @Test
    void scoringDatasetsWithoutDerivedGatewayAreInsufficientAndNeverHitPublicAkShare() {
        ScriptedTransport transport = new ScriptedTransport();
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(transport, CLOCK));

        for (MarketDatasetCode dataset : List.of(
                MarketDatasetCode.MARKET_DAILY,
                MarketDatasetCode.VALUATION,
                MarketDatasetCode.BREADTH,
                MarketDatasetCode.CROSS_MARKET)) {
            RiskProviderBatch result = provider.fetch(dataset.code(), currentRequest(List.of(MARKET)));
            assertThat(result.qualityStatus()).as(dataset.code())
                    .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(result.errorMessage()).contains("derived gateway");
        }
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void emptyMarketSnapshotIsInsufficientRatherThanAFormalZeroObservation() {
        ScriptedTransport transport = new ScriptedTransport();
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(transport, CLOCK));

        RiskProviderBatch result = provider.fetch(
                MarketDatasetCode.CN_A_STOCK_MASTER.code(), currentRequest(List.of(STOCK)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.errorMessage()).contains("no records");
    }

    @Test
    void derivedGatewayPreservesInstantWhenConvertingOffsetTimestampsToRuntimeZone() {
        ScriptedTransport nativeTransport = new ScriptedTransport();
        ScriptedTransport derivedTransport = new ScriptedTransport();
        derivedTransport.valuation = List.of(Map.ofEntries(
                Map.entry("objectType", "market"),
                Map.entry("objectId", "CN-A"),
                Map.entry("tradeDate", "2026-07-18"),
                Map.entry("TTM(滚动)市盈率", "20"),
                Map.entry("riskFreeYield", "0.018"),
                Map.entry("observedAt", "2026-07-18T07:00:00+00:00"),
                Map.entry("availableAt", "2026-07-18T08:00:00+00:00")
        ));
        derivedTransport.nextCursor = "valuation-page-2";
        AkToolsMarketRiskSourceClient client = new AkToolsMarketRiskSourceClient(
                nativeTransport, derivedTransport, Clock.fixed(
                        Instant.parse("2026-07-18T10:00:00Z"), ZoneId.of("Asia/Shanghai")));

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.VALUATION, currentRequest(List.of(MARKET)));

        assertThat(nativeTransport.calls).isEmpty();
        assertThat(derivedTransport.calls).containsExactly(new Call(
                "/api/risk/valuation",
                Map.of(
                        "start_date", "20210718",
                        "end_date", "20260718",
                        "objects", "market:CN-A"
                )));
        assertThat(result.records()).singleElement().satisfies(record -> {
            ValuationPoint valuation = (ValuationPoint) record;
            assertThat(valuation.peTtm()).isEqualByComparingTo("20");
            assertThat(valuation.observedAt()).isEqualTo(
                    LocalDateTime.of(2026, 7, 18, 15, 0));
            assertThat(valuation.availableAt()).isEqualTo(
                    LocalDateTime.of(2026, 7, 18, 16, 0));
        });
        assertThat(result.nextCheckpoint()).satisfies(checkpoint -> {
            assertThat(checkpoint.datasetCode()).isEqualTo("valuation");
            assertThat(checkpoint.cursor()).isEqualTo("valuation-page-2");
        });
    }

    @Test
    void derivedGatewayPartialRowsRemainInsufficientAndNeverAdvanceCheckpoint() {
        ScriptedTransport nativeTransport = new ScriptedTransport();
        ScriptedTransport derivedTransport = new ScriptedTransport();
        derivedTransport.valuation = List.of(Map.ofEntries(
                Map.entry("objectType", "market"),
                Map.entry("objectId", "CN-A"),
                Map.entry("tradeDate", "2026-07-18"),
                Map.entry("peTtm", "20"),
                Map.entry("riskFreeYield", "0.018"),
                Map.entry("observedAt", "2026-07-18T15:00:00"),
                Map.entry("availableAt", "2026-07-18T16:00:00")
        ));
        derivedTransport.nextCursor = "must-not-advance";
        derivedTransport.earliestAvailableDate = LocalDate.of(2025, 1, 1);
        derivedTransport.historyComplete = false;
        derivedTransport.historyGapReason = "only recent valuation history";
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(nativeTransport, derivedTransport, CLOCK));

        RiskProviderBatch result = provider.fetch(
                MarketDatasetCode.VALUATION.code(), currentRequest(List.of(MARKET)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.errorMessage()).contains("only recent valuation history");
        assertThat(result.observations()).isNotEmpty().allSatisfy(observation -> {
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(observation.value()).isNull();
            assertThat(observation.attributes()).containsKeys(
                    "auditValue", "sourceQuality", "partialHistoryReason");
        });
        assertThat(result.nextCheckpoint()).isNull();
    }

    @Test
    void derivedGatewayMissingEarliestDateIsIncompleteEvenWhenFlagClaimsComplete() {
        ScriptedTransport nativeTransport = new ScriptedTransport();
        ScriptedTransport derivedTransport = new ScriptedTransport();
        derivedTransport.valuation = List.of(Map.ofEntries(
                Map.entry("objectType", "market"),
                Map.entry("objectId", "CN-A"),
                Map.entry("tradeDate", "2026-07-18"),
                Map.entry("peTtm", "20"),
                Map.entry("riskFreeYield", "0.018"),
                Map.entry("observedAt", "2026-07-18T15:00:00"),
                Map.entry("availableAt", "2026-07-18T16:00:00")
        ));
        derivedTransport.earliestAvailableDate = null;
        derivedTransport.historyComplete = true;
        derivedTransport.nextCursor = "must-not-advance";
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(nativeTransport, derivedTransport, CLOCK));

        RiskProviderBatch result = provider.fetch(
                MarketDatasetCode.VALUATION.code(), currentRequest(List.of(MARKET)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.errorMessage()).contains("earliestAvailableDate");
        assertThat(result.nextCheckpoint()).isNull();
    }

    @Test
    void emptyDerivedResponseIsInsufficientRatherThanAFormalZeroObservation() {
        ScriptedTransport nativeTransport = new ScriptedTransport();
        ScriptedTransport derivedTransport = new ScriptedTransport();
        MarketRiskDataProvider provider = new MarketRiskDataProvider(
                new AkToolsMarketRiskSourceClient(nativeTransport, derivedTransport, CLOCK));

        RiskProviderBatch result = provider.fetch(
                MarketDatasetCode.VALUATION.code(), currentRequest(List.of(MARKET)));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.errorMessage()).contains("no records");
    }

    @Test
    void noArgumentGlobalSnapshotIsSharedAcrossStockChunksForTheSameEvaluationDay() {
        ScriptedTransport transport = new ScriptedTransport();
        transport.stockMaster = List.of(
                Map.of("code", "600519", "name", "贵州茅台"),
                Map.of("code", "000001", "name", "平安银行"));
        AkToolsMarketRiskSourceClient client = new AkToolsMarketRiskSourceClient(transport, CLOCK);

        client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, currentRequest(List.of(STOCK)));
        client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, currentRequest(List.of(
                new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ"))));

        assertThat(transport.calls).containsExactly(
                new Call("/api/public/stock_info_a_code_name", Map.of()));
    }

    @Test
    void restClientTransportAcceptsArrayAndDataEnvelopeResponsesWithoutInventingParameters() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/sw_index_first_info"))
                .andRespond(withSuccess("""
                        {"data":[{"行业代码":"801010.SI","TTM(滚动)市盈率":18.5}],
                         "meta":{"nextCursor":"page-2","earliestAvailableDate":"2021-07-18",
                                 "historyComplete":false,"insufficientHistory":true,
                                 "historyGapReason":"history page missing"}}
                        """, MediaType.APPLICATION_JSON));
        RestClientMarketRiskHttpTransport transport = new RestClientMarketRiskHttpTransport(
                "http://127.0.0.1:8090", builder, new ObjectMapper());

        MarketRiskHttpResponse response = transport.getResponse(
                "/api/public/sw_index_first_info", Map.of());

        assertThat(response.rows()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("行业代码", "801010.SI");
            assertThat(row).containsEntry("TTM(滚动)市盈率", 18.5);
        });
        assertThat(response.nextCursor()).isEqualTo("page-2");
        assertThat(response.earliestAvailableDate()).isEqualTo(LocalDate.of(2021, 7, 18));
        assertThat(response.historyComplete()).isFalse();
        assertThat(response.insufficientHistory()).isTrue();
        assertThat(response.historyGapReason()).isEqualTo("history page missing");
        server.verify();
    }

    private RiskProviderRequest currentRequest(List<RiskObjectKey> objects) {
        return new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2021, 7, 18), LocalDate.of(2026, 7, 18), null);
    }

    private RiskProviderRequest historicalRequest(List<RiskObjectKey> objects) {
        return new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2021, 7, 17), LocalDate.of(2026, 7, 17), null);
    }

    private RiskProviderRequest snapshotRequest(List<RiskObjectKey> objects) {
        return new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18), null);
    }

    private record Call(String endpoint, Map<String, String> query) {
    }

    private static final class ScriptedTransport implements MarketRiskHttpTransport {
        private final List<Call> calls = new ArrayList<>();
        private List<Map<String, Object>> stockMaster = List.of();
        private List<Map<String, Object>> sw1Catalog = List.of();
        private List<Map<String, Object>> sw1Components = List.of();
        private List<Map<String, Object>> valuation = List.of();
        private String nextCursor;
        private LocalDate earliestAvailableDate = LocalDate.of(2021, 7, 18);
        private boolean historyComplete = true;
        private boolean insufficientHistory;
        private String historyGapReason;

        @Override
        public List<Map<String, Object>> get(String endpoint, Map<String, String> query) {
            calls.add(new Call(endpoint, query));
            return switch (endpoint) {
                case "/api/public/stock_info_a_code_name" -> stockMaster;
                case "/api/public/sw_index_first_info" -> sw1Catalog;
                case "/api/public/index_component_sw" -> sw1Components;
                case "/api/risk/valuation" -> valuation;
                default -> List.of();
            };
        }

        @Override
        public MarketRiskHttpResponse getResponse(String endpoint, Map<String, String> query) {
            return new MarketRiskHttpResponse(
                    get(endpoint, query), nextCursor, earliestAvailableDate,
                    historyComplete, insufficientHistory, historyGapReason);
        }
    }
}
