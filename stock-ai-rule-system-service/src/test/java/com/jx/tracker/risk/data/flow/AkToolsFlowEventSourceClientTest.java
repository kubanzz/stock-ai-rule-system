package com.jx.tracker.risk.data.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AkToolsFlowEventSourceClientTest {

    @Test
    void mapsNormalizedAkToolsRowsAndCoverageMetadataWithoutCredentials() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_margin_account_info"))
                .andRespond(withSuccess("""
                        {
                          "data": [{
                            "recordId": "margin-600519-20260718",
                            "cursor": "20260718:600519",
                            "objectId": "600519.SH",
                            "tradeDate": "2026-07-18",
                            "occurredAt": "2026-07-18T15:00:00",
                            "observedAt": "2026-07-18T15:30:00",
                            "availableAt": "2026-07-18T16:00:00",
                            "value": 123.4,
                            "unit": "亿元",
                            "eventCode": "balance",
                            "title": "融资余额",
                            "attributes": {"previousBalance": 120.0}
                          }],
                          "meta": {
                            "nextCursor": "20260718:600519",
                            "earliestAvailableDate": "2025-01-01",
                            "historyComplete": false
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        AkToolsFlowEventSourceClient client = new AkToolsFlowEventSourceClient(
                "http://127.0.0.1:8090", builder, new ObjectMapper(), fixedClock());

        FlowEventSourceBatch batch = client.fetch(new FlowEventSourceRequest(
                FlowEventDataset.MARGIN_FINANCING,
                request(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18))));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.source()).isEqualTo("aktools/akshare");
        assertThat(batch.records()).singleElement().satisfies(record -> {
            assertThat(record.object().objectId()).isEqualTo("600519.SH");
            assertThat(record.value()).isEqualByComparingTo("123.4");
            assertThat(record.attributes()).containsEntry("previousBalance", 120.0);
        });
        assertThat(batch.historyComplete()).isFalse();
        assertThat(batch.earliestAvailableDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        server.verify();
    }

    @Test
    void emptySuccessfulAkToolsResponseIsValidZero() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_zh_a_disclosure_report_cninfo")))
                .andRespond(withSuccess("{\"data\":[],\"meta\":{\"nextCursor\":\"end\"}}",
                        MediaType.APPLICATION_JSON));
        AkToolsFlowEventSourceClient client = new AkToolsFlowEventSourceClient(
                "http://127.0.0.1:8090", builder, new ObjectMapper(), fixedClock());

        FlowEventSourceBatch batch = client.fetch(new FlowEventSourceRequest(
                FlowEventDataset.STOCK_ANNOUNCEMENT,
                request(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 18))));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(batch.nextCursor()).isEqualTo("end");
        server.verify();
    }

    @Test
    void earningsForecastBackfillQueriesEveryCompletedQuarterInRange() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260331"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://127.0.0.1:8090/api/public/stock_yjyg_em")))
                .andExpect(queryParam("date", "20260630"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        AkToolsFlowEventSourceClient client = new AkToolsFlowEventSourceClient(
                "http://127.0.0.1:8090", builder, new ObjectMapper(), fixedClock());

        FlowEventSourceBatch batch = client.fetch(new FlowEventSourceRequest(
                FlowEventDataset.EARNINGS_FORECAST,
                request(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 7, 18))));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        server.verify();
    }

    private RiskProviderRequest request(LocalDate start, LocalDate end) {
        return new RiskProviderRequest(
                List.of(new RiskObjectKey(RiskObjectType.STOCK, "600519.SH")),
                List.of(RiskHorizon.SHORT_TERM), start, end, null);
    }

    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2026-07-18T09:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }
}
