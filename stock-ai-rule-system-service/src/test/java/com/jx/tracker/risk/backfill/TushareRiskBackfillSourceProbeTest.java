package com.jx.tracker.risk.backfill;

import com.jx.tracker.risk.data.tushare.TushareRiskException;
import com.jx.tracker.risk.data.tushare.TushareRiskHttpClient;
import com.jx.tracker.risk.data.tushare.TushareRiskRequest;
import com.jx.tracker.risk.data.tushare.TushareRiskResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TushareRiskBackfillSourceProbeTest {

    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 10);
    private static final List<String> REQUIRED_APIS = List.of(
            "trade_cal", "stock_basic", "index_member_all", "daily", "adj_factor",
            "index_daily", "daily_basic", "yc_cb", "index_global", "margin",
            "margin_detail", "etf_basic", "fund_basic", "fund_share", "fund_nav",
            "fund_daily", "forecast_vip", "share_float", "stk_holdertrade");

    private final TushareRiskHttpClient httpClient = mock(TushareRiskHttpClient.class);
    private final RiskBackfillSourceProbe fallbackProbe = mock(RiskBackfillSourceProbe.class);

    @Test
    void probesEveryRequiredApiWithSmallAuditableRequests() {
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            return new TushareRiskResponse(
                    request.apiName(), fields(request),
                    List.of(Map.of("trade_date", "20260710")));
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isTrue();
        assertThat(result.source()).isEqualTo("tushare");
        assertThat(result.summaries())
                .extracting(SourceProbeSummary::api)
                .containsExactlyElementsOf(REQUIRED_APIS);
        assertThat(result.summaries())
                .allSatisfy(summary -> {
                    assertThat(summary.rowCount()).isEqualTo(1);
                    assertThat(summary.quality())
                            .isEqualTo(SourceProbeSummary.Quality.AVAILABLE);
                });
        assertThat(result.summaries())
                .filteredOn(summary -> summary.api().equals("daily"))
                .singleElement()
                .satisfies(summary -> {
                    assertThat(summary.earliestDate()).isEqualTo(END_DATE);
                    assertThat(summary.latestDate()).isEqualTo(END_DATE);
                });

        ArgumentCaptor<TushareRiskRequest> captor =
                ArgumentCaptor.forClass(TushareRiskRequest.class);
        verify(httpClient, org.mockito.Mockito.times(REQUIRED_APIS.size()))
                .query(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TushareRiskRequest::apiName)
                .containsExactlyElementsOf(REQUIRED_APIS);
        assertThat(captor.getAllValues())
                .allSatisfy(request -> assertThat(request.fields()).doesNotContain("token"));
        assertThat(captor.getAllValues().stream()
                .filter(request -> request.apiName().equals("daily"))
                .findFirst().orElseThrow().params())
                .containsEntry("ts_code", "000001.SZ")
                .containsKeys("start_date", "end_date");
        assertThat(captor.getAllValues().stream()
                .filter(request -> request.apiName().equals("forecast_vip"))
                .findFirst().orElseThrow().params())
                .containsEntry("ts_code", "000001.SZ")
                .containsKey("period");
    }

    @Test
    void classifiesSuccessfulEmptyResponsesAsValidZero() {
        when(httpClient.query(any())).thenAnswer(invocation ->
                empty(invocation.getArgument(0)));

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isTrue();
        assertThat(result.summaries()).hasSize(REQUIRED_APIS.size());
        assertThat(result.summaries())
                .allSatisfy(summary -> {
                    assertThat(summary.rowCount()).isZero();
                    assertThat(summary.quality())
                            .isEqualTo(SourceProbeSummary.Quality.VALID_ZERO);
                });
    }

    @Test
    void classifiesSuccessfulEmptyResponsesWithoutFieldMetadataAsValidZero() {
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            return new TushareRiskResponse(request.apiName(), List.of(), List.of());
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isTrue();
        assertThat(result.summaries()).hasSize(REQUIRED_APIS.size());
        assertThat(result.summaries())
                .allSatisfy(summary -> {
                    assertThat(summary.rowCount()).isZero();
                    assertThat(summary.quality())
                            .isEqualTo(SourceProbeSummary.Quality.VALID_ZERO);
                });
    }

    @Test
    void classifiesSuccessfulEmptyResponsesWithPartialFieldMetadataAsValidZero() {
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            return new TushareRiskResponse(
                    request.apiName(), List.of("ts_code"), List.of());
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isTrue();
        assertThat(result.summaries()).hasSize(REQUIRED_APIS.size());
        assertThat(result.summaries())
                .allSatisfy(summary -> assertThat(summary.quality())
                        .isEqualTo(SourceProbeSummary.Quality.VALID_ZERO));
    }

    @Test
    void reportsTheExactPermissionDeniedApiWithoutLeakingVendorText() throws Exception {
        String secret = "probe-secret-must-never-appear";
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            if (request.apiName().equals("yc_cb")) {
                throw vendorFailure(
                        TushareRiskException.Category.PERMISSION,
                        "yc_cb", 2002, "permission denied token=" + secret);
            }
            return empty(request);
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail())
                .contains("api=yc_cb", "category=PERMISSION", "code=2002")
                .doesNotContain(secret)
                .doesNotContain("token=");
        assertThat(result.summaries())
                .extracting(SourceProbeSummary::api)
                .contains("daily_basic")
                .doesNotContain("yc_cb");
    }

    @Test
    void classifiesRateAndNetworkFailuresWithoutEchoingExceptionMessages() throws Exception {
        String secret = "network-secret-must-never-appear";
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            if (request.apiName().equals("margin")) {
                throw vendorFailure(
                        TushareRiskException.Category.RATE_LIMIT,
                        "margin", 429, "rate limited token=" + secret);
            }
            if (request.apiName().equals("fund_nav")) {
                throw new IllegalStateException("socket failed token=" + secret);
            }
            return empty(request);
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail())
                .contains("api=margin", "category=RATE_LIMIT", "code=429")
                .contains("api=fund_nav", "category=NETWORK")
                .doesNotContain(secret)
                .doesNotContain("token=");
    }

    @Test
    void reportsResponseMappingFailureAgainstTheRequestedApi() {
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            return request.apiName().equals("index_global")
                    ? new TushareRiskResponse(
                            "wrong_api", fields(request), List.of())
                    : empty(request);
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail()).contains("api=index_global category=MAPPING");
    }

    @Test
    void reportsInvalidDateMappingAgainstTheExactApi() {
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            if (request.apiName().equals("index_global")) {
                return new TushareRiskResponse(
                        "index_global", fields(request),
                        List.of(Map.of("trade_date", "not-a-date")));
            }
            return empty(request);
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail()).contains("api=index_global category=MAPPING");
    }

    @Test
    void reportsMissingRequiredResponseFieldAgainstTheExactApi() {
        when(httpClient.query(any())).thenAnswer(invocation -> {
            TushareRiskRequest request = invocation.getArgument(0);
            if (request.apiName().equals("daily_basic")) {
                return new TushareRiskResponse(
                        "daily_basic",
                        List.of("ts_code", "trade_date"),
                        List.of(Map.of(
                                "ts_code", "000001.SZ",
                                "trade_date", "20260710")));
            }
            return empty(request);
        });

        SourceProbeResult result = probe().probeTushare(END_DATE);

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail()).contains("api=daily_basic category=MAPPING");
    }

    @Test
    void delegatesExistingAkToolsAndDerivedGatewayProbes() {
        when(fallbackProbe.probeAkTools(END_DATE))
                .thenReturn(SourceProbeResult.reachable("aktools"));
        when(fallbackProbe.probeDerivedGateway(END_DATE))
                .thenReturn(SourceProbeResult.reachable("derived-gateway"));

        TushareRiskBackfillSourceProbe probe = probe();

        assertThat(probe.probeAkTools(END_DATE).reachable()).isTrue();
        assertThat(probe.probeDerivedGateway(END_DATE).reachable()).isTrue();
    }

    private TushareRiskBackfillSourceProbe probe() {
        return new TushareRiskBackfillSourceProbe(httpClient, fallbackProbe);
    }

    private TushareRiskResponse empty(TushareRiskRequest request) {
        return new TushareRiskResponse(
                request.apiName(), fields(request), List.of());
    }

    private List<String> fields(TushareRiskRequest request) {
        return List.of(request.fields().split(","));
    }

    private TushareRiskException vendorFailure(
            TushareRiskException.Category category,
            String api,
            int code,
            String message
    ) throws Exception {
        Method method = TushareRiskException.class.getDeclaredMethod(
                "vendor", TushareRiskException.Category.class,
                String.class, int.class, String.class);
        method.setAccessible(true);
        return (TushareRiskException) method.invoke(null, category, api, code, message);
    }
}
