package com.jx.tracker.risk.backfill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.risk.runtime.RiskWarningProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRiskBackfillSourceProbeTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acceptsAkToolsArrayAndDerivedEnvelopeContracts() {
        respond("/api/public/stock_info_a_code_name", "[]", 200);
        respond("/api/risk/market-daily",
                "{\"data\":[],\"meta\":{\"historyComplete\":false}}", 200);
        server.start();
        HttpRiskBackfillSourceProbe probe = probe(baseUrl);

        assertThat(probe.probeAkTools(LocalDate.of(2026, 7, 10)).reachable()).isTrue();
        assertThat(probe.probeDerivedGateway(LocalDate.of(2026, 7, 10)).reachable()).isTrue();
    }

    @Test
    void rejectsDerivedResponseWithoutHistoryCompletenessMetadata() {
        respond("/api/public/stock_info_a_code_name", "{\"data\":[]}", 200);
        respond("/api/risk/market-daily", "{\"data\":[],\"meta\":{}}", 200);
        server.start();
        HttpRiskBackfillSourceProbe probe = probe(baseUrl);

        SourceProbeResult result = probe.probeDerivedGateway(LocalDate.of(2026, 7, 10));

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail()).contains("historyComplete");
    }

    @Test
    void reportsAnUnconfiguredDerivedGatewayWithoutExposingCredentials() {
        RiskWarningProperties risk = new RiskWarningProperties();
        risk.setAkToolsBaseUrl(baseUrl);
        risk.setDerivedGatewayBaseUrl(" ");
        MarketDataProviderProperties market = new MarketDataProviderProperties();
        HttpRiskBackfillSourceProbe probe = new HttpRiskBackfillSourceProbe(
                risk, market, RestClient.builder(), new ObjectMapper());

        SourceProbeResult result = probe.probeDerivedGateway(LocalDate.of(2026, 7, 10));

        assertThat(result.reachable()).isFalse();
        assertThat(result.detail()).isEqualTo("derived gateway is not configured");
    }

    private HttpRiskBackfillSourceProbe probe(String url) {
        RiskWarningProperties risk = new RiskWarningProperties();
        risk.setAkToolsBaseUrl(url);
        risk.setDerivedGatewayBaseUrl(url);
        return new HttpRiskBackfillSourceProbe(
                risk, new MarketDataProviderProperties(), RestClient.builder(), new ObjectMapper());
    }

    private void respond(String path, String body, int status) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
