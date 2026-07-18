package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.provider.RiskProviderRequest;

@FunctionalInterface
public interface MarketRiskSourceClient {

    MarketSourceBatch fetch(MarketDatasetCode dataset, RiskProviderRequest request);
}
