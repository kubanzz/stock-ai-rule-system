package com.jx.tracker.market.data.service;

public interface MarketDataBootstrapService {

    BootstrapAccepted start(String market, String triggerBy);

    record BootstrapAccepted(String jobId, String market, String status) {
    }
}
