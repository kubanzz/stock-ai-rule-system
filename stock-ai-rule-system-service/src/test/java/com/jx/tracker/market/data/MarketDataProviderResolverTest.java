package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.AkToolsMarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MockMarketDataProvider;
import com.jx.tracker.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketDataProviderResolverTest {

    @Test
    void applicationConfigDoesNotCommitAProviderToken() throws IOException {
        String application;
        try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).as("/application.yml").isNotNull();
            application = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(application)
                .contains("token: ${MARKET_DATA_PROVIDER_TOKEN:${TUSHARE_TOKEN:}}");
    }

    @Test
    void usesAkToolsWithoutFallingBackToMockWhenConfiguredExplicitly() {
        MarketDataProviderProperties properties = new MarketDataProviderProperties();
        properties.setType("aktools");
        properties.setAkToolsBaseUrl("http://127.0.0.1:8090");

        MarketDataProviderResolver resolver = new MarketDataProviderResolver(
                properties,
                new MockMarketDataProvider(),
                RestClient.builder(),
                new ObjectMapper()
        );

        var selection = resolver.resolve();

        assertThat(selection.provider()).isInstanceOf(AkToolsMarketDataProvider.class);
        assertThat(selection.dataSource()).isEqualTo("aktools/akshare");
        assertThat(selection.fallback()).isFalse();
    }

    @Test
    void fallsBackToMockProviderWhenExternalProviderHasNoToken() {
        MarketDataProviderProperties properties = new MarketDataProviderProperties();
        properties.setType("tushare");
        properties.setFallbackType("mock");
        properties.setToken(" ");

        MarketDataProviderResolver resolver = new MarketDataProviderResolver(
                properties,
                new MockMarketDataProvider(),
                RestClient.builder(),
                new ObjectMapper()
        );

        var selection = resolver.resolve();

        assertThat(selection.provider()).isInstanceOf(MockMarketDataProvider.class);
        assertThat(selection.dataSource()).isEqualTo("mock");
        assertThat(selection.fallback()).isTrue();
        assertThat(selection.fallbackReason()).contains("token");
    }

    @Test
    void usesMockProviderWhenConfiguredExplicitly() {
        MarketDataProviderProperties properties = new MarketDataProviderProperties();
        properties.setType("mock");

        MarketDataProviderResolver resolver = new MarketDataProviderResolver(
                properties,
                new MockMarketDataProvider(),
                RestClient.builder(),
                new ObjectMapper()
        );

        var selection = resolver.resolve();

        assertThat(selection.provider()).isInstanceOf(MockMarketDataProvider.class);
        assertThat(selection.dataSource()).isEqualTo("mock");
        assertThat(selection.fallback()).isFalse();
    }

    @Test
    void fallsBackToMockProviderWhenConfiguredCsvFileCannotBeRead() {
        MarketDataProviderProperties properties = new MarketDataProviderProperties();
        properties.setType("csv");
        properties.setStockListCsvPath("/path/not/exist/stock-list.csv");

        MarketDataProviderResolver resolver = new MarketDataProviderResolver(
                properties,
                new MockMarketDataProvider(),
                RestClient.builder(),
                new ObjectMapper()
        );

        var selection = resolver.resolve();

        assertThat(selection.provider()).isInstanceOf(MockMarketDataProvider.class);
        assertThat(selection.dataSource()).isEqualTo("mock");
        assertThat(selection.fallback()).isTrue();
        assertThat(selection.fallbackReason()).contains("csv provider failed");
    }

    @Test
    void fallsBackToMockProviderWhenTushareRemoteApiUrlIsPlainHttp() {
        MarketDataProviderProperties properties = new MarketDataProviderProperties();
        properties.setType("tushare");
        properties.setFallbackType("mock");
        properties.setToken("test-token");
        properties.setApiUrl("http://api.tushare.pro");

        MarketDataProviderResolver resolver = new MarketDataProviderResolver(
                properties,
                new MockMarketDataProvider(),
                RestClient.builder(),
                new ObjectMapper()
        );

        var selection = resolver.resolve();

        assertThat(selection.provider()).isInstanceOf(MockMarketDataProvider.class);
        assertThat(selection.dataSource()).isEqualTo("mock");
        assertThat(selection.fallback()).isTrue();
        assertThat(selection.fallbackReason()).contains("HTTPS");
    }

    @Test
    void configuredReadTimeoutEndsAnUnresponsiveProviderRequest() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                Thread.sleep(1500);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            MarketDataProviderProperties properties = new MarketDataProviderProperties();
            properties.setType("tushare");
            properties.setToken("test-token");
            properties.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setConnectTimeout(Duration.ofMillis(500));
            properties.setReadTimeout(Duration.ofMillis(100));
            var resolver = new MarketDataProviderResolver(properties,
                    new MockMarketDataProvider(), RestClient.builder(), new ObjectMapper());
            var provider = resolver.resolve().provider();

            assertThatThrownBy(() -> provider.fetchDailyQuotes("600519.SH",
                    LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 30)))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("超时");
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
