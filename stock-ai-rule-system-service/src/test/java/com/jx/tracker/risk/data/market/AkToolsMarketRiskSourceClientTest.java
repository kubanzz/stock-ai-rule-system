package com.jx.tracker.risk.data.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AkToolsMarketRiskSourceClientTest {

    @Test
    void usesInjectedTransportAndNormalizesFixedValuationResponse() {
        CapturingTransport transport = new CapturingTransport(List.of(Map.of(
                "objectId", "CN-A",
                "tradeDate", "2026-07-18",
                "peTtm", "18.5",
                "observedAt", "2026-07-18T15:00:00",
                "availableAt", "2026-07-18T16:00:00"
        )));
        AkToolsMarketRiskSourceClient client = new AkToolsMarketRiskSourceClient(
                transport,
                Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
        );

        MarketSourceBatch result = client.fetch(MarketDatasetCode.VALUATION, request());

        assertThat(transport.endpoint).isEqualTo("/api/public/sw_index_first_info");
        assertThat(transport.query).containsEntry("end_date", "20260718");
        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record).isInstanceOf(ValuationPoint.class);
            ValuationPoint valuation = (ValuationPoint) record;
            assertThat(valuation.earningsYield()).isPositive();
            assertThat(valuation.riskFreeYield()).isNull();
        });
        assertThat(result.source()).isEqualTo("aktools");
    }

    @Test
    void restClientTransportAcceptsArrayAndDataEnvelopeResponses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/sw_index_first_info")))
                .andExpect(queryParam("end_date", "20260718"))
                .andRespond(withSuccess("""
                        {"data":[{"行业代码":"801010.SI","市盈率":18.5}]}
                        """, MediaType.APPLICATION_JSON));
        RestClientMarketRiskHttpTransport transport = new RestClientMarketRiskHttpTransport(
                "http://127.0.0.1:8090", builder, new ObjectMapper()
        );

        List<Map<String, Object>> rows = transport.get(
                "/api/public/sw_index_first_info", Map.of("end_date", "20260718")
        );

        assertThat(rows).singleElement().satisfies(row ->
                assertThat(row).containsEntry("行业代码", "801010.SI"));
        server.verify();
    }

    @Test
    void stockMasterSnapshotUsesRequestEndDateWhenEndpointHasNoDate() {
        AkToolsMarketRiskSourceClient client = new AkToolsMarketRiskSourceClient(
                new CapturingTransport(List.of(Map.of("code", "600519", "name", "贵州茅台"))),
                Clock.fixed(Instant.parse("2026-07-18T10:00:00Z"), ZoneOffset.UTC)
        );

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, request());

        assertThat(result.records()).singleElement().satisfies(record -> {
            assertThat(record).isInstanceOf(StockMasterPoint.class);
            assertThat(record.tradeDate()).isEqualTo(LocalDate.of(2026, 7, 18));
            assertThat(record.object()).isEqualTo(new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"));
        });
    }

    private RiskProviderRequest request() {
        return new RiskProviderRequest(
                List.of(new RiskObjectKey(RiskObjectType.MARKET, "CN-A")),
                List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2021, 7, 18),
                LocalDate.of(2026, 7, 18),
                null
        );
    }

    private static final class CapturingTransport implements MarketRiskHttpTransport {
        private final List<Map<String, Object>> response;
        private String endpoint;
        private Map<String, String> query;

        private CapturingTransport(List<Map<String, Object>> response) {
            this.response = response;
        }

        @Override
        public List<Map<String, Object>> get(String endpoint, Map<String, String> query) {
            this.endpoint = endpoint;
            this.query = query;
            return response;
        }
    }
}
