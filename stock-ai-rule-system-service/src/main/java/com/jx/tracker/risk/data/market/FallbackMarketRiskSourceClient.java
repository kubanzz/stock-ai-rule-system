package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.provider.RiskProviderRequest;

/** Uses a secondary market source only when the primary source cannot provide complete history. */
public final class FallbackMarketRiskSourceClient implements MarketRiskSourceClient {

    private final MarketRiskSourceClient primary;
    private final MarketRiskSourceClient fallback;

    public FallbackMarketRiskSourceClient(
            MarketRiskSourceClient primary,
            MarketRiskSourceClient fallback
    ) {
        if (primary == null || fallback == null) {
            throw new IllegalArgumentException("primary and fallback market sources are required");
        }
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public MarketSourceBatch fetch(MarketDatasetCode dataset, RiskProviderRequest request) {
        MarketSourceBatch primaryBatch = primary.fetch(dataset, request);
        if (!requiresFallback(primaryBatch.qualityStatus())) {
            return primaryBatch;
        }
        MarketSourceBatch fallbackBatch = fallback.fetch(dataset, request);
        if (!primaryBatch.records().isEmpty()) {
            return new MarketSourceBatch(
                    combinedSource(primaryBatch, fallbackBatch),
                    primaryBatch.records(),
                    primaryBatch.nextCheckpoint(),
                    fallbackBatch.fetchedAt(),
                    primaryBatch.qualityStatus(),
                    combinedReason(primaryBatch, fallbackBatch)
                            + "; primary partial records retained");
        }
        return new MarketSourceBatch(
                combinedSource(primaryBatch, fallbackBatch),
                fallbackBatch.records(),
                fallbackBatch.nextCheckpoint(),
                fallbackBatch.fetchedAt(),
                fallbackBatch.qualityStatus(),
                combinedReason(primaryBatch, fallbackBatch));
    }

    private boolean requiresFallback(RiskDataQualityStatus status) {
        return status == RiskDataQualityStatus.UNAVAILABLE
                || status == RiskDataQualityStatus.INSUFFICIENT_HISTORY;
    }

    private String combinedSource(
            MarketSourceBatch primaryBatch,
            MarketSourceBatch fallbackBatch
    ) {
        return primaryBatch.source() + "->" + fallbackBatch.source();
    }

    private String combinedReason(
            MarketSourceBatch primaryBatch,
            MarketSourceBatch fallbackBatch
    ) {
        String fallbackAudit = fallbackBatch.failureReason();
        if (fallbackAudit == null || fallbackAudit.isBlank()) {
            fallbackAudit = "quality=" + fallbackBatch.qualityStatus().getCode();
        }
        return "primary[" + primaryBatch.source() + "]: " + primaryBatch.failureReason()
                + "; fallback[" + fallbackBatch.source() + "]: " + fallbackAudit;
    }
}
