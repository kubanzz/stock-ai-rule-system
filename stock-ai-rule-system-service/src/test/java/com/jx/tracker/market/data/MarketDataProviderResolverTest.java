package com.jx.tracker.market.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.market.data.provider.MarketDataProviderProperties;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MockMarketDataProvider;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataProviderResolverTest {

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
}
