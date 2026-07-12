package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.AkToolsMarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MockMarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataProviderResolverTest {

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
}
