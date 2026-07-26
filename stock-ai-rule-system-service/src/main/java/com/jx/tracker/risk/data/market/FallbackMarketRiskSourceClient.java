package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.provider.RiskProviderRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        List<MarketSourceRecord> mergedRecords = mergeRecords(primaryBatch, fallbackBatch);
        boolean fallbackComplete = formal(fallbackBatch.qualityStatus());
        RiskDataQualityStatus qualityStatus = mergedQuality(
                mergedRecords, fallbackBatch, fallbackComplete);
        String auditReason = combinedReason(primaryBatch, fallbackBatch);
        return new MarketSourceBatch(
                combinedSource(primaryBatch, fallbackBatch),
                mergedRecords,
                fallbackComplete ? fallbackBatch.nextCheckpoint() : null,
                fallbackBatch.fetchedAt(),
                qualityStatus,
                formal(qualityStatus) ? null : auditReason,
                auditReason);
    }

    private boolean requiresFallback(RiskDataQualityStatus status) {
        return status == RiskDataQualityStatus.UNAVAILABLE
                || status == RiskDataQualityStatus.INSUFFICIENT_HISTORY;
    }

    private List<MarketSourceRecord> mergeRecords(
            MarketSourceBatch primaryBatch,
            MarketSourceBatch fallbackBatch
    ) {
        Map<String, MarketSourceRecord> merged = new LinkedHashMap<>();
        primaryBatch.records().forEach(record ->
                merged.put(recordIdentity(record), record));
        fallbackBatch.records().forEach(record -> {
            String identity = recordIdentity(record);
            MarketSourceRecord primaryRecord = merged.get(identity);
            if (primaryRecord == null
                    || (!formal(primaryRecord.qualityStatus())
                    && formal(record.qualityStatus()))) {
                merged.put(identity, record);
            }
        });
        return List.copyOf(merged.values());
    }

    private RiskDataQualityStatus mergedQuality(
            List<MarketSourceRecord> mergedRecords,
            MarketSourceBatch fallbackBatch,
            boolean fallbackComplete
    ) {
        if (fallbackComplete) {
            if (fallbackBatch.qualityStatus() == RiskDataQualityStatus.VALID_ZERO
                    && mergedRecords.isEmpty()) {
                return RiskDataQualityStatus.VALID_ZERO;
            }
            return RiskDataQualityStatus.AVAILABLE;
        }
        if (!mergedRecords.isEmpty()) {
            return RiskDataQualityStatus.INSUFFICIENT_HISTORY;
        }
        return fallbackBatch.qualityStatus();
    }

    private boolean formal(RiskDataQualityStatus status) {
        return status == RiskDataQualityStatus.AVAILABLE
                || status == RiskDataQualityStatus.VALID_ZERO;
    }

    private String recordIdentity(MarketSourceRecord record) {
        if (record instanceof IndustryExposure exposure) {
            return String.join(
                    ":",
                    record.getClass().getName(),
                    exposure.stock().objectType().getCode(),
                    exposure.stock().objectId(),
                    exposure.sector().objectId(),
                    exposure.validFrom().toString());
        }
        return String.join(
                ":",
                record.getClass().getName(),
                record.object().objectType().getCode(),
                record.object().objectId(),
                record.tradeDate().toString());
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
        return "primary[" + primaryBatch.source() + "]: quality="
                + primaryBatch.qualityStatus().getCode()
                + ", reason=" + primaryBatch.failureReason()
                + "; fallback[" + fallbackBatch.source() + "]: " + fallbackAudit;
    }
}
