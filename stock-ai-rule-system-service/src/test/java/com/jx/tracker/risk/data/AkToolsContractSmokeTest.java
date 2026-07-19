package com.jx.tracker.risk.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKShare 1.18.64 / AKTools 0.0.91 真实契约冒烟。
 * 默认跳过；原生端点使用 RISK_AKTOOLS_IT，衍生网关使用 RISK_DERIVED_GATEWAY_IT 分别开启。
 */
class AkToolsContractSmokeTest {

    private static final String AUDITED_AKSHARE_VERSION = "1.18.64";
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @TestFactory
    @EnabledIfEnvironmentVariable(named = "RISK_AKTOOLS_IT", matches = "(?i:true|1)")
    Stream<DynamicTest> verifiesTwelveExactAkShareSignaturesAndResponseShapes() {
        return endpointSpecs().stream().map(spec -> DynamicTest.dynamicTest(
                "AKShare " + AUDITED_AKSHARE_VERSION + " - " + spec.name(), () -> verify(spec)));
    }

    @TestFactory
    @EnabledIfEnvironmentVariable(named = "RISK_DERIVED_GATEWAY_IT", matches = "(?i:true|1)")
    Stream<DynamicTest> verifiesDerivedGatewayEnvelopeAndQualityMetadata() {
        return derivedEndpointSpecs().stream().map(spec -> DynamicTest.dynamicTest(
                "derived gateway - " + spec.name(), () -> verifyDerived(spec)));
    }

    private void verifyDerived(DerivedEndpointSpec spec) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(derivedUri(spec.path(), spec.query()))
                .timeout(Duration.ofSeconds(spec.path().endsWith("/breadth")
                        ? Long.parseLong(environment(
                        "RISK_DERIVED_GATEWAY_BREADTH_TIMEOUT_SECONDS", "1800"))
                        : 300))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).as(spec.name()).isEqualTo(200);
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        assertThat(root.path("data").isArray()).as(spec.name() + " data").isTrue();
        JsonNode meta = root.path("meta");
        assertThat(meta.isObject()).as(spec.name() + " meta").isTrue();
        assertThat(meta.has("historyComplete")).isTrue();
        assertThat(meta.has("insufficientHistory")).isTrue();
        assertThat(meta.has("source")).isTrue();
        assertThat(meta.has("sourceVersion")).isTrue();
        assertThat(meta.has("calculationVersion")).isTrue();
        assertThat(meta.has("fetchedAt")).isTrue();
        if (spec.etfGap()) {
            assertThat(root.path("data").isEmpty()).isTrue();
            assertThat(meta.path("historyComplete").asBoolean()).isFalse();
            assertThat(meta.path("historyGapReason").asText()).contains("redemption");
        }
        System.out.printf(
                "derived endpoint=%s rows=%d earliest=%s complete=%s insufficient=%s%n",
                spec.name(), root.path("data").size(), meta.path("earliestAvailableDate").asText(),
                meta.path("historyComplete").asBoolean(), meta.path("insufficientHistory").asBoolean());
    }

    private void verify(EndpointSpec spec) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(spec.path(), spec.query()))
                .timeout(Duration.ofSeconds(180))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).as(spec.name()).isEqualTo(200);
        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        JsonNode rows = root != null && root.isArray() ? root : root == null ? null : root.path("data");
        assertThat(rows).as(spec.name() + " rows").isNotNull();
        assertThat(rows.isArray()).as(spec.name() + " rows must be an array").isTrue();
        if (!spec.allowEmpty()) {
            assertThat(rows.isEmpty()).as(spec.name() + " must return a representative row").isFalse();
        }
        if (!rows.isEmpty()) {
            JsonNode first = rows.get(0);
            for (List<String> alternatives : spec.requiredFieldAlternatives()) {
                assertThat(alternatives.stream().anyMatch(first::has))
                        .as(spec.name() + " field alternatives " + alternatives)
                        .isTrue();
            }
        }
    }

    private URI uri(String path, Map<String, String> query) {
        String baseUrl = environment("RISK_AKTOOLS_BASE_URL", "http://127.0.0.1:8090")
                .replaceAll("/+$", "");
        if (query.isEmpty()) {
            return URI.create(baseUrl + path);
        }
        String encodedQuery = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        return URI.create(baseUrl + path + "?" + encodedQuery);
    }

    private URI derivedUri(String path, Map<String, String> query) {
        String baseUrl = environment(
                "RISK_WARNING_DERIVED_GATEWAY_BASE_URL", "http://127.0.0.1:18090")
                .replaceAll("/+$", "");
        String encodedQuery = query.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElseThrow();
        return URI.create(baseUrl + path + "?" + encodedQuery);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private List<EndpointSpec> endpointSpecs() {
        String stock = environment("RISK_AKTOOLS_STOCK_SYMBOL", "600519");
        String sector = environment("RISK_AKTOOLS_SW1_SYMBOL", "801780");
        String index = environment("RISK_AKTOOLS_INDEX_SYMBOL", "sh000001");
        LocalDate endDate = LocalDate.parse(environment(
                "RISK_AKTOOLS_SMOKE_END_DATE", LocalDate.now().minusDays(1).toString()));
        LocalDate startDate = endDate.minusDays(30);
        String reportDate = environment(
                "RISK_AKTOOLS_REPORT_DATE", previousQuarterEnd(endDate).format(BASIC_DATE));

        return List.of(
                spec("stock master: no arguments", "/api/public/stock_info_a_code_name", Map.of(),
                        false, List.of(List.of("code", "代码"), List.of("name", "名称"))),
                spec("SW1 catalog: no arguments", "/api/public/sw_index_first_info", Map.of(),
                        false, List.of(List.of("行业代码"), List.of("行业名称"))),
                spec("SW component: symbol only", "/api/public/index_component_sw",
                        Map.of("symbol", sector), false,
                        List.of(List.of("证券代码"), List.of("证券名称"))),
                spec("index daily: symbol only", "/api/public/stock_zh_index_daily",
                        Map.of("symbol", index), false,
                        List.of(List.of("date", "日期"), List.of("close", "收盘"))),
                spec("A-share spot: no arguments", "/api/public/stock_zh_a_spot_em", Map.of(),
                        false, List.of(List.of("代码"), List.of("最新价"))),
                spec("global spot: no arguments", "/api/public/index_global_spot_em", Map.of(),
                        false, List.of(List.of("名称"), List.of("最新价"))),
                spec("margin account: no arguments", "/api/public/stock_margin_account_info", Map.of(),
                        false, List.of(List.of("日期"), List.of("融资余额", "融资余额(亿)"))),
                spec("ETF spot: no arguments", "/api/public/fund_etf_spot_em", Map.of(),
                        false, List.of(List.of("代码"), List.of("主力净流入-净额"))),
                spec("earnings forecast: report date only", "/api/public/stock_yjyg_em",
                        Map.of("date", reportDate), false,
                        List.of(List.of("股票代码"), List.of("公告日期"))),
                spec("announcement: symbol and date range",
                        "/api/public/stock_zh_a_disclosure_report_cninfo",
                        orderedQuery(
                                "symbol", stock,
                                "start_date", startDate.format(BASIC_DATE),
                                "end_date", endDate.format(BASIC_DATE)),
                        true, List.of(List.of("代码"), List.of("公告标题"))),
                spec("share unlock: symbol only", "/api/public/stock_restricted_release_queue_sina",
                        Map.of("symbol", stock), true,
                        List.of(List.of("代码"), List.of("解禁日期"))),
                spec("share changes: default full market", "/api/public/stock_ggcg_em", Map.of(),
                        false, List.of(List.of("代码"), List.of("持股变动信息-增减")))
        );
    }

    private List<DerivedEndpointSpec> derivedEndpointSpecs() {
        String stock = environment("RISK_AKTOOLS_STOCK_SYMBOL", "600519") + ".SH";
        String rawDate = environment("RISK_DERIVED_GATEWAY_SMOKE_DATE", "2026-07-17");
        String compactDate = LocalDate.parse(rawDate).format(BASIC_DATE);
        String breadthStartDate = LocalDate.parse(environment(
                "RISK_DERIVED_GATEWAY_BREADTH_START_DATE", "2015-07-17"))
                .format(BASIC_DATE);
        Map<String, String> marketQuery = orderedQuery(
                "start_date", compactDate, "end_date", compactDate, "objects", "market:CN-A");
        Map<String, String> breadthQuery = orderedQuery(
                "start_date", breadthStartDate, "end_date", compactDate, "objects", "market:CN-A");
        Map<String, String> stockQuery = orderedQuery(
                "start_date", compactDate, "end_date", compactDate, "objects", "stock:" + stock);
        return List.of(
                derivedSpec("market-daily", "/api/risk/market-daily", marketQuery, false),
                derivedSpec("valuation", "/api/risk/valuation", stockQuery, false),
                derivedSpec("breadth", "/api/risk/breadth", breadthQuery, false),
                derivedSpec("cross-market", "/api/risk/cross-market", marketQuery, false),
                derivedSpec("sw1-membership", "/api/risk/sw1-membership", stockQuery, false),
                derivedSpec("etf-redemption", "/api/risk/etf-redemption", marketQuery, true)
        );
    }

    private DerivedEndpointSpec derivedSpec(
            String name, String path, Map<String, String> query, boolean etfGap
    ) {
        return new DerivedEndpointSpec(name, path, query, etfGap);
    }

    private EndpointSpec spec(
            String name,
            String path,
            Map<String, String> query,
            boolean allowEmpty,
            List<List<String>> requiredFieldAlternatives
    ) {
        return new EndpointSpec(name, path, query, allowEmpty, requiredFieldAlternatives);
    }

    private Map<String, String> orderedQuery(String... pairs) {
        Map<String, String> query = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            query.put(pairs[index], pairs[index + 1]);
        }
        return query;
    }

    private LocalDate previousQuarterEnd(LocalDate date) {
        int quarterStartMonth = ((date.getMonthValue() - 1) / 3) * 3 + 1;
        return LocalDate.of(date.getYear(), quarterStartMonth, 1).minusDays(1);
    }

    private String environment(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private record EndpointSpec(
            String name,
            String path,
            Map<String, String> query,
            boolean allowEmpty,
            List<List<String>> requiredFieldAlternatives
    ) {
    }

    private record DerivedEndpointSpec(
            String name,
            String path,
            Map<String, String> query,
            boolean etfGap
    ) {
    }
}
