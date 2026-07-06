package com.jx.tracker.market.data.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class MarketDataProviderResolver {

    private final MarketDataProviderProperties properties;

    private final MockMarketDataProvider mockMarketDataProvider;

    private final RestClient.Builder restClientBuilder;

    private final ObjectMapper objectMapper;

    public MarketDataProviderResolver(
            MarketDataProviderProperties properties,
            MockMarketDataProvider mockMarketDataProvider,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.mockMarketDataProvider = mockMarketDataProvider;
        this.restClientBuilder = restClientBuilder;
        this.objectMapper = objectMapper;
    }

    public MarketDataProviderSelection resolve() {
        String type = providerType(properties.getType());
        return switch (type) {
            case "mock" -> mock(false, null);
            case "csv" -> csv(false, null);
            case "tushare" -> resolveTushare();
            default -> fallback("unsupported provider type: " + type);
        };
    }

    private MarketDataProviderSelection resolveTushare() {
        if (!StringUtils.hasText(properties.getToken())) {
            return fallback("tushare token is missing");
        }
        try {
            return new MarketDataProviderSelection(
                    new TushareMarketDataProvider(properties.getToken(), properties.getApiUrl(), restClientBuilder, objectMapper),
                    "tushare",
                    false,
                    null
            );
        } catch (RuntimeException ex) {
            return fallback("tushare provider failed: " + ex.getMessage());
        }
    }

    private MarketDataProviderSelection fallback(String reason) {
        String fallbackType = providerType(properties.getFallbackType());
        if ("csv".equals(fallbackType) && hasCsvPath()) {
            return csv(true, reason);
        }
        return new MarketDataProviderSelection(mockMarketDataProvider, "mock", true, reason);
    }

    private MarketDataProviderSelection csv(boolean fallback, String reason) {
        if (!hasCsvPath()) {
            return fallback ? mock(true, reason) : new MarketDataProviderSelection(mockMarketDataProvider, "mock", true, "csv path is missing");
        }
        try {
            return new MarketDataProviderSelection(
                    CsvMarketDataProvider.fromFiles(
                            properties.getStockListCsvPath(),
                            properties.getDailyQuoteCsvPath(),
                            properties.getTradeCalendarCsvPath()
                    ),
                    "csv",
                    fallback,
                    reason
            );
        } catch (RuntimeException ex) {
            return mock(true, appendReason(reason, "csv provider failed: " + ex.getMessage()));
        }
    }

    private MarketDataProviderSelection mock(boolean fallback, String reason) {
        return new MarketDataProviderSelection(mockMarketDataProvider, "mock", fallback, reason);
    }

    private boolean hasCsvPath() {
        return StringUtils.hasText(properties.getStockListCsvPath())
                || StringUtils.hasText(properties.getDailyQuoteCsvPath())
                || StringUtils.hasText(properties.getTradeCalendarCsvPath());
    }

    private String providerType(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "mock";
    }

    private String appendReason(String reason, String csvReason) {
        if (!StringUtils.hasText(reason)) {
            return csvReason;
        }
        return reason + "; " + csvReason;
    }
}
