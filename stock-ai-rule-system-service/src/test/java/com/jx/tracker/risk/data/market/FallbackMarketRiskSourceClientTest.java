package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FallbackMarketRiskSourceClientTest {

    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 7, 18, 19, 0);
    private static final RiskObjectKey STOCK =
            new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private static final RiskObjectKey OTHER_STOCK =
            new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ");
    private static final RiskProviderRequest REQUEST = new RiskProviderRequest(
            List.of(STOCK), List.of(RiskHorizon.SHORT_TERM),
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 18), null);

    @Test
    void availablePrimaryDoesNotCallFallback() {
        MarketSourceBatch primaryBatch = available("tushare", stockMaster("tushare"));
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", stockMaster("aktools"));
                });

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(result).isSameAs(primaryBatch);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void validZeroPrimaryDoesNotCallFallback() {
        MarketSourceBatch primaryBatch = new MarketSourceBatch(
                "tushare", List.of(), null, FETCHED_AT,
                RiskDataQualityStatus.VALID_ZERO, null);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", stockMaster("aktools"));
                });

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(result).isSameAs(primaryBatch);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void unavailablePrimaryUsesFallbackAndKeepsPrimaryFailureAuditable() {
        MarketSourceBatch primaryBatch = new MarketSourceBatch(
                "tushare", List.of(), null, FETCHED_AT,
                RiskDataQualityStatus.UNAVAILABLE, "daily permission denied");
        MarketSourceRecord fallbackRecord = stockMaster("aktools");
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> available("aktools", fallbackRecord));

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).containsExactly(fallbackRecord);
        assertThat(result.source()).contains("tushare", "aktools");
        assertThat(result.failureReason()).isNull();
        assertThat(result.fallbackReason()).contains("daily permission denied");
    }

    @Test
    void emptyInsufficientHistoryPrimaryUsesFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        MarketSourceRecord fallbackRecord = stockMaster("aktools");
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> MarketSourceBatch.insufficientHistory(
                        "tushare", "daily returned no rows", FETCHED_AT),
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", fallbackRecord);
                });

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(fallbackCalls).hasValue(1);
        assertThat(result.records()).containsExactly(fallbackRecord);
        assertThat(result.failureReason()).isNull();
        assertThat(result.fallbackReason()).contains("daily returned no rows");
    }

    @Test
    void latestBreadthDoesNotStartFullMarketFallbackWhenTushareIsPartial() {
        MarketSourceRecord primaryRecord = stockMaster(
                STOCK, "tushare", "主源审计", RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        MarketSourceBatch primaryBatch = MarketSourceBatch.partialHistory(
                "tushare", List.of(primaryRecord), null,
                "breadth coverage is below 95%", FETCHED_AT);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", stockMaster("aktools"));
                });
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        RiskProviderRequest latestRequest = new RiskProviderRequest(
                List.of(STOCK), List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2024, 7, 18), tradeDate, tradeDate, null);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.BREADTH, latestRequest);

        assertThat(result).isSameAs(primaryBatch);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void latestBreadthDoesNotStartFullMarketFallbackWhenTushareIsUnavailable() {
        MarketSourceBatch primaryBatch = new MarketSourceBatch(
                "tushare", List.of(), null, FETCHED_AT,
                RiskDataQualityStatus.UNAVAILABLE, "daily request failed");
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", stockMaster("aktools"));
                });
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        RiskProviderRequest latestRequest = new RiskProviderRequest(
                List.of(STOCK), List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2024, 7, 18), tradeDate, tradeDate, null);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.BREADTH, latestRequest);

        assertThat(result).isSameAs(primaryBatch);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void latestNonBreadthDatasetStillUsesFallbackWhenTushareIsUnavailable() {
        MarketSourceBatch primaryBatch = new MarketSourceBatch(
                "tushare", List.of(), null, FETCHED_AT,
                RiskDataQualityStatus.UNAVAILABLE, "daily request failed");
        AtomicInteger fallbackCalls = new AtomicInteger();
        MarketSourceRecord fallbackRecord = stockMaster("aktools");
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", fallbackRecord);
                });
        LocalDate tradeDate = LocalDate.of(2026, 7, 18);
        RiskProviderRequest latestRequest = new RiskProviderRequest(
                List.of(STOCK), List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2024, 7, 18), tradeDate, tradeDate, null);

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.MARKET_DAILY, latestRequest);

        assertThat(result.records()).containsExactly(fallbackRecord);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void historicalBreadthStillUsesFallbackForIncompleteTushareHistory() {
        MarketSourceBatch primaryBatch = MarketSourceBatch.insufficientHistory(
                "tushare", "historical breadth is incomplete", FETCHED_AT);
        AtomicInteger fallbackCalls = new AtomicInteger();
        MarketSourceRecord fallbackRecord = stockMaster("aktools");
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", fallbackRecord);
                });

        MarketSourceBatch result = client.fetch(
                MarketDatasetCode.BREADTH, REQUEST);

        assertThat(result.records()).containsExactly(fallbackRecord);
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void partialPrimaryMergesAnAvailableFallbackAndKeepsFormalPrimaryIdentity() {
        MarketSourceRecord primaryRecord = stockMaster(
                STOCK, "tushare", "主源名称", RiskDataQualityStatus.AVAILABLE);
        MarketSourceRecord fallbackSameIdentity = stockMaster(
                STOCK, "aktools", "补源名称", RiskDataQualityStatus.AVAILABLE);
        MarketSourceRecord fallbackMissingIdentity = stockMaster(
                OTHER_STOCK, "aktools", "平安银行", RiskDataQualityStatus.AVAILABLE);
        MarketSourceBatch primaryBatch = MarketSourceBatch.partialHistory(
                "tushare", List.of(primaryRecord), null,
                "stock_basic is a current snapshot", FETCHED_AT);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return new MarketSourceBatch(
                            "aktools",
                            List.of(fallbackSameIdentity, fallbackMissingIdentity),
                            null,
                            FETCHED_AT);
                });

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(fallbackCalls).hasValue(1);
        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records())
                .containsExactly(primaryRecord, fallbackMissingIdentity)
                .doesNotContain(fallbackSameIdentity);
        assertThat(result.source()).contains("tushare", "aktools");
        assertThat(result.failureReason()).isNull();
        assertThat(result.fallbackReason())
                .contains("stock_basic is a current snapshot", "aktools");
    }

    @Test
    void validZeroFallbackCannotPromotePartialPrimaryRecordsToAvailable() {
        MarketSourceRecord primaryRecord = stockMaster(
                STOCK, "tushare", "主源名称", RiskDataQualityStatus.AVAILABLE);
        RiskIngestionCheckpoint fallbackCheckpoint = new RiskIngestionCheckpoint(
                MarketDatasetCode.CN_A_STOCK_MASTER.code(),
                "cn-a",
                "fallback-complete",
                FETCHED_AT);
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> MarketSourceBatch.partialHistory(
                        "tushare",
                        List.of(primaryRecord),
                        null,
                        "primary history is incomplete",
                        FETCHED_AT),
                (dataset, request) -> new MarketSourceBatch(
                        "aktools",
                        List.of(),
                        fallbackCheckpoint,
                        FETCHED_AT,
                        RiskDataQualityStatus.VALID_ZERO,
                        null));

        MarketSourceBatch result =
                client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).containsExactly(primaryRecord);
        assertThat(result.nextCheckpoint()).isNull();
        assertThat(result.failureReason())
                .contains("primary history is incomplete", "valid_zero");
        assertThat(result.fallbackReason()).isEqualTo(result.failureReason());
    }

    @Test
    void availableFallbackReplacesAnAuditOnlyPrimaryRecordWithTheSameIdentity() {
        MarketSourceRecord auditOnlyPrimary = stockMaster(
                STOCK, "tushare", "主源审计", RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        MarketSourceRecord formalFallback = stockMaster(
                STOCK, "aktools", "贵州茅台", RiskDataQualityStatus.AVAILABLE);
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> MarketSourceBatch.partialHistory(
                        "tushare",
                        List.of(auditOnlyPrimary),
                        null,
                        "stock_basic mapping is incomplete",
                        FETCHED_AT),
                (dataset, request) -> available("aktools", formalFallback));

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.records()).containsExactly(formalFallback);
        assertThat(result.fallbackReason())
                .contains("stock_basic mapping is incomplete", "insufficient_history");
    }

    @Test
    void twoPartialSourcesKeepTheMergedAuditRecordsWithoutAdvancingCheckpoint() {
        MarketSourceRecord primaryRecord = stockMaster(
                STOCK, "tushare", "贵州茅台", RiskDataQualityStatus.AVAILABLE);
        MarketSourceRecord fallbackRecord = stockMaster(
                OTHER_STOCK, "aktools", "平安银行", RiskDataQualityStatus.AVAILABLE);
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> MarketSourceBatch.partialHistory(
                        "tushare", List.of(primaryRecord), REQUEST.checkpoint(),
                        "primary truncated", FETCHED_AT),
                (dataset, request) -> MarketSourceBatch.partialHistory(
                        "aktools", List.of(fallbackRecord), REQUEST.checkpoint(),
                        "fallback truncated", FETCHED_AT));

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).containsExactly(primaryRecord, fallbackRecord);
        assertThat(result.nextCheckpoint()).isNull();
        assertThat(result.failureReason()).contains("primary truncated", "fallback truncated");
        assertThat(result.fallbackReason()).isEqualTo(result.failureReason());
    }

    private static MarketSourceBatch available(String source, MarketSourceRecord record) {
        return new MarketSourceBatch(source, List.of(record), null, FETCHED_AT);
    }

    private static StockMasterPoint stockMaster(String source) {
        return stockMaster(STOCK, source, "贵州茅台", RiskDataQualityStatus.AVAILABLE);
    }

    private static StockMasterPoint stockMaster(
            RiskObjectKey stock,
            String source,
            String name,
            RiskDataQualityStatus qualityStatus
    ) {
        return new StockMasterPoint(
                stock, FETCHED_AT.toLocalDate(), name,
                LocalDate.of(2001, 8, 27), FETCHED_AT, FETCHED_AT,
                source, qualityStatus);
    }
}
