package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
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
        assertThat(result.failureReason()).contains("daily permission denied");
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
        assertThat(result.failureReason()).contains("daily returned no rows");
    }

    @Test
    void partialPrimaryCallsFallbackButRetainsPrimaryAuditRecords() {
        MarketSourceRecord primaryRecord = stockMaster("tushare");
        MarketSourceBatch primaryBatch = MarketSourceBatch.partialHistory(
                "tushare", List.of(primaryRecord), null,
                "stock_basic is a current snapshot", FETCHED_AT);
        AtomicInteger fallbackCalls = new AtomicInteger();
        FallbackMarketRiskSourceClient client = new FallbackMarketRiskSourceClient(
                (dataset, request) -> primaryBatch,
                (dataset, request) -> {
                    fallbackCalls.incrementAndGet();
                    return available("aktools", stockMaster("aktools"));
                });

        MarketSourceBatch result = client.fetch(MarketDatasetCode.CN_A_STOCK_MASTER, REQUEST);

        assertThat(fallbackCalls).hasValue(1);
        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.records()).containsExactly(primaryRecord);
        assertThat(result.source()).contains("tushare", "aktools");
        assertThat(result.failureReason())
                .contains("stock_basic is a current snapshot", "primary partial records retained");
    }

    private static MarketSourceBatch available(String source, MarketSourceRecord record) {
        return new MarketSourceBatch(source, List.of(record), null, FETCHED_AT);
    }

    private static StockMasterPoint stockMaster(String source) {
        return new StockMasterPoint(
                STOCK, FETCHED_AT.toLocalDate(), "贵州茅台",
                LocalDate.of(2001, 8, 27), FETCHED_AT, FETCHED_AT,
                source, RiskDataQualityStatus.AVAILABLE);
    }
}
