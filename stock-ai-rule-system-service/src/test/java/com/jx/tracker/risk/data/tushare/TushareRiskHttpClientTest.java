package com.jx.tracker.risk.data.tushare;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TushareRiskHttpClientTest {

    private static final String LOCAL_URL = "http://localhost:18080";
    private static final String TEST_TOKEN = "t1";

    @Test
    void postsCompleteRequestAndMapsColumnArraysToImmutableRows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "api_name": "stock_basic",
                          "token": "t1",
                          "params": {"list_status": "L"},
                          "fields": "ts_code,name,list_date"
                        }
                        """, JsonCompareMode.STRICT))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "msg": null,
                          "data": {
                            "fields": ["ts_code", "name", "list_date"],
                            "items": [
                              ["000001.SZ", "平安银行", "19910403"],
                              ["600000.SH", "浦发银行", null]
                            ]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TushareRiskResponse response = client(builder).query(new TushareRiskRequest(
                "stock_basic", Map.of("list_status", "L"), "ts_code,name,list_date"));

        assertThat(response.apiName()).isEqualTo("stock_basic");
        assertThat(response.fields()).containsExactly("ts_code", "name", "list_date");
        assertThat(response.rows()).containsExactly(
                Map.of("ts_code", "000001.SZ", "name", "平安银行", "list_date", "19910403"),
                mapWithNull("ts_code", "600000.SH", "name", "浦发银行", "list_date"));
        assertThatThrownBy(() -> response.rows().add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.rows().getFirst().put("name", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(response.toString()).doesNotContain(TEST_TOKEN);
        server.verify();
    }

    @Test
    void returnsSuccessfulEmptyResult() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "fields": ["ts_code"],
                            "items": []
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TushareRiskResponse response = client(builder).query(
                new TushareRiskRequest("stock_basic", Map.of(), "ts_code"));

        assertThat(response.fields()).containsExactly("ts_code");
        assertThat(response.rows()).isEmpty();
        server.verify();
    }

    @Test
    void rejectsSuccessfulResponseWithoutFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "items": [["000001.SZ"]]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TushareRiskException exception = catchThrowableOfType(
                TushareRiskException.class,
                () -> client(builder).query(
                        new TushareRiskRequest("stock_basic", Map.of(), "ts_code")));

        assertThat(exception.category()).isEqualTo(TushareRiskException.Category.PARSE);
        assertThat(exception.apiName()).isEqualTo("stock_basic");
        assertThat(exception.getMessage()).contains("fields").doesNotContain(TEST_TOKEN);
        assertThat(exception.getCause()).isNull();
        server.verify();
    }

    @Test
    void rejectsRowsWhoseColumnCountDoesNotMatchFields() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "fields": ["ts_code", "name"],
                            "items": [["000001.SZ"]]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TushareRiskException exception = catchThrowableOfType(
                TushareRiskException.class,
                () -> client(builder).query(
                        new TushareRiskRequest("stock_basic", Map.of(), "ts_code,name")));

        assertThat(exception.category()).isEqualTo(TushareRiskException.Category.PARSE);
        assertThat(exception.getMessage()).contains("column").doesNotContain(TEST_TOKEN);
        server.verify();
    }

    @Test
    void normalizesPermissionErrorWithoutRetrying() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": -2001,
                          "msg": "抱歉，您没有访问该接口的权限 token=t1",
                          "data": null
                        }
                        """, MediaType.APPLICATION_JSON));
        List<Duration> waits = new ArrayList<>();

        TushareRiskException exception = catchThrowableOfType(
                TushareRiskException.class,
                () -> client(builder, 2, waits).query(
                        new TushareRiskRequest("bak_basic", Map.of(), "ts_code")));

        assertThat(exception.category()).isEqualTo(TushareRiskException.Category.PERMISSION);
        assertThat(exception.apiName()).isEqualTo("bak_basic");
        assertThat(exception.code()).isEqualTo(-2001);
        assertThat(exception.vendorMessage())
                .isEqualTo("抱歉，您没有访问该接口的权限 token=[REDACTED]");
        assertThat(exception.getMessage())
                .contains("bak_basic", "-2001", "抱歉，您没有访问该接口的权限")
                .doesNotContain(TEST_TOKEN);
        assertThat(waits).isEmpty();
        server.verify();
    }

    @Test
    void retriesRateLimitWithInjectedZeroWaitThenReturnsSuccess() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(2), requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": -2002,
                          "msg": "每分钟最多访问一次",
                          "data": null
                        }
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "fields": ["ts_code"],
                            "items": [["000001.SZ"]]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));
        List<Duration> waits = new ArrayList<>();

        TushareRiskResponse response = client(builder, 2, waits).query(
                new TushareRiskRequest("stock_basic", Map.of(), "ts_code"));

        assertThat(response.rows()).containsExactly(Map.of("ts_code", "000001.SZ"));
        assertThat(waits).containsExactly(Duration.ZERO, Duration.ZERO);
        server.verify();
    }

    @Test
    void stopsAfterBoundedRateLimitRetries() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(3), requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": -2002,
                          "msg": "rate limit exceeded",
                          "data": null
                        }
                        """, MediaType.APPLICATION_JSON));
        List<Duration> waits = new ArrayList<>();

        TushareRiskException exception = catchThrowableOfType(
                TushareRiskException.class,
                () -> client(builder, 2, waits).query(
                        new TushareRiskRequest("stock_basic", Map.of(), "ts_code")));

        assertThat(exception.category()).isEqualTo(TushareRiskException.Category.RATE_LIMIT);
        assertThat(exception.code()).isEqualTo(-2002);
        assertThat(waits).containsExactly(Duration.ZERO, Duration.ZERO);
        server.verify();
    }

    @Test
    void sanitizesNetworkFailureAndDoesNotExposeItsCause() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withException(new IOException("network failed while sending token=t1")));

        TushareRiskException exception = catchThrowableOfType(
                TushareRiskException.class,
                () -> client(builder).query(
                        new TushareRiskRequest("stock_basic", Map.of(), "ts_code")));

        assertThat(exception.category()).isEqualTo(TushareRiskException.Category.REMOTE);
        assertThat(exception.getMessage())
                .contains("stock_basic")
                .doesNotContain(TEST_TOKEN, "token=");
        assertThat(exception.vendorMessage()).isNull();
        assertThat(exception.getCause()).isNull();
        server.verify();
    }

    @Test
    void sanitizesParsingFailureAndDoesNotExposeResponseBody() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {"code":0,"data":BROKEN,"echoed_token":"t1"}
                        """, MediaType.APPLICATION_JSON));

        TushareRiskException exception = catchThrowableOfType(
                TushareRiskException.class,
                () -> client(builder).query(
                        new TushareRiskRequest("stock_basic", Map.of(), "ts_code")));

        assertThat(exception.category()).isEqualTo(TushareRiskException.Category.PARSE);
        assertThat(exception.getMessage())
                .contains("stock_basic")
                .doesNotContain(TEST_TOKEN, "echoed_token", "BROKEN");
        assertThat(exception.getCause()).isNull();
        server.verify();
    }

    @Test
    void rejectsBlankTokenAtConstructionWithoutEchoingIt() {
        assertThatThrownBy(() -> new TushareRiskHttpClient(
                "  ", LOCAL_URL, RestClient.builder(), new ObjectMapper()))
                .isInstanceOf(TushareRiskException.class)
                .hasMessageContaining("token")
                .hasMessageNotContaining(TEST_TOKEN);
    }

    @Test
    void rejectsPlainHttpForRemoteHosts() {
        assertThatThrownBy(() -> new TushareRiskHttpClient(
                TEST_TOKEN, "http://api.tushare.pro", RestClient.builder(), new ObjectMapper()))
                .isInstanceOf(TushareRiskException.class)
                .hasMessageContaining("HTTPS")
                .hasMessageNotContaining(TEST_TOKEN);
    }

    @Test
    void acceptsPlainHttpForLocalhost() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(LOCAL_URL))
                .andRespond(withSuccess("""
                        {
                          "code": 0,
                          "data": {
                            "fields": ["ts_code"],
                            "items": []
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        TushareRiskResponse response = client(builder).query(
                new TushareRiskRequest("stock_basic", Map.of(), "ts_code"));

        assertThat(response.rows()).isEmpty();
        server.verify();
    }

    private TushareRiskHttpClient client(RestClient.Builder builder) {
        return new TushareRiskHttpClient(TEST_TOKEN, LOCAL_URL, builder, new ObjectMapper());
    }

    private TushareRiskHttpClient client(
            RestClient.Builder builder,
            int maxRetries,
            List<Duration> waits
    ) {
        return new TushareRiskHttpClient(
                TEST_TOKEN,
                LOCAL_URL,
                builder,
                new ObjectMapper(),
                new TushareRiskHttpClient.RetryPolicy(maxRetries, Duration.ZERO),
                waits::add);
    }

    private Map<String, Object> mapWithNull(
            String key1,
            Object value1,
            String key2,
            Object value2,
            String nullKey
    ) {
        java.util.LinkedHashMap<String, Object> row = new java.util.LinkedHashMap<>();
        row.put(key1, value1);
        row.put(key2, value2);
        row.put(nullKey, null);
        return row;
    }
}
