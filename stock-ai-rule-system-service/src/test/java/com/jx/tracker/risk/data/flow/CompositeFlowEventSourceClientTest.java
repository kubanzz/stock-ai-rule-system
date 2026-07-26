package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeFlowEventSourceClientTest {

    private static final LocalDateTime FETCHED_AT =
            LocalDateTime.of(2026, 7, 18, 20, 0);

    @Test
    void directlyRoutesAnnouncementsWithoutCallingStructuredPrimary() {
        AtomicInteger primaryCalls = new AtomicInteger();
        AtomicInteger announcementCalls = new AtomicInteger();
        FlowEventSourceClient primary = request -> {
            primaryCalls.incrementAndGet();
            return FlowEventSourceBatch.unavailable("tushare", "unexpected", FETCHED_AT);
        };
        FlowEventSourceClient announcements = request -> {
            announcementCalls.incrementAndGet();
            return FlowEventSourceBatch.validZero(
                    "aktools/cninfo", "announcement:2026-07-18", FETCHED_AT);
        };
        CompositeFlowEventSourceClient client = new CompositeFlowEventSourceClient(
                primary, List.of(), Map.of(
                FlowEventDataset.STOCK_ANNOUNCEMENT.code(), announcements));

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.STOCK_ANNOUNCEMENT,
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH")));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(result.source()).isEqualTo("aktools/cninfo");
        assertThat(primaryCalls).hasValue(0);
        assertThat(announcementCalls).hasValue(1);
    }

    @Test
    void successfulZeroFromPrimaryDoesNotTriggerSupplement() {
        AtomicInteger supplementCalls = new AtomicInteger();
        FlowEventSupplementProvider supplement = new FlowEventSupplementProvider() {
            @Override
            public String providerCode() {
                return "aktools";
            }

            @Override
            public int priority() {
                return 100;
            }

            @Override
            public boolean supports(String datasetCode) {
                return true;
            }

            @Override
            public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
                supplementCalls.incrementAndGet();
                return FlowEventSourceBatch.unavailable(
                        "aktools", "must not be called", FETCHED_AT);
            }
        };
        CompositeFlowEventSourceClient client = new CompositeFlowEventSourceClient(
                request -> FlowEventSourceBatch.validZero(
                        "tushare", "forecast:2026-07-18", FETCHED_AT),
                List.of(supplement));

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.EARNINGS_FORECAST,
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH")));

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(result.source()).isEqualTo("tushare");
        assertThat(supplementCalls).hasValue(0);
    }

    private static FlowEventSourceRequest request(
            FlowEventDataset dataset,
            RiskObjectKey object
    ) {
        return new FlowEventSourceRequest(dataset, new RiskProviderRequest(
                List.of(object),
                List.of(RiskHorizon.SHORT_TERM),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 18),
                null));
    }
}
