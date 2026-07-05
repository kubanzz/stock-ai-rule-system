package com.jx.tracker.market.data.provider;

public record MarketDataProviderSelection(
        MarketDataProvider provider,
        String dataSource,
        boolean fallback,
        String fallbackReason) {
}
