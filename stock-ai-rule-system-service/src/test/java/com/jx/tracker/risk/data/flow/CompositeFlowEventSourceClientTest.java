package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Test
    void supplementValidZeroDoesNotUpgradePartialPrimaryRecords() {
        FlowEventSourceRecord partialRecord = new FlowEventSourceRecord(
                "forecast-partial",
                "2026-07-17T00:00|forecast-partial",
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"),
                LocalDate.of(2026, 7, 16),
                LocalDateTime.of(2026, 7, 16, 0, 0),
                LocalDateTime.of(2026, 7, 16, 0, 0),
                LocalDateTime.of(2026, 7, 17, 0, 0),
                java.math.BigDecimal.valueOf(-30),
                "percent",
                "forecast_change",
                "预减",
                Map.of("economicMeaning", "cash_flow"));
        FlowEventSourceBatch primary = new FlowEventSourceBatch(
                "tushare", List.of(partialRecord),
                RiskDataQualityStatus.AVAILABLE,
                "forecast history is partial", null,
                LocalDate.of(2026, 7, 16), false, FETCHED_AT, null);
        FlowEventSupplementProvider validZeroSupplement =
                new FlowEventSupplementProvider() {
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
                    public FlowEventSourceBatch fetch(
                            FlowEventSourceRequest request
                    ) {
                        return FlowEventSourceBatch.validZero(
                                "aktools", "forecast:2026-07-18",
                                FETCHED_AT.plusMinutes(1));
                    }
                };
        CompositeFlowEventSourceClient client =
                new CompositeFlowEventSourceClient(
                        request -> primary,
                        List.of(validZeroSupplement));

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.EARNINGS_FORECAST,
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH")));

        assertThat(result.records()).containsExactly(partialRecord);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .isEqualTo("forecast history is partial");
        assertThat(result.fallbackReason())
                .contains("forecast history is partial")
                .contains("aktools valid_zero");
    }

    @Test
    void mergesTheSameBusinessEventOnlyOnceAcrossSources() {
        RiskObjectKey object = new RiskObjectKey(
                RiskObjectType.STOCK, "600519.SH");
        FlowEventSourceRecord primaryRecord = forecastRecord(
                "tushare-row-17", object,
                LocalDateTime.of(2026, 7, 17, 0, 0));
        FlowEventSourceRecord supplementRecord = forecastRecord(
                "aktools-row-91", object,
                LocalDateTime.of(2026, 7, 18, 0, 0));
        FlowEventSourceBatch primary = new FlowEventSourceBatch(
                "tushare", List.of(primaryRecord),
                RiskDataQualityStatus.AVAILABLE,
                "forecast history is partial", null,
                LocalDate.of(2026, 7, 16), false,
                FETCHED_AT, null);
        FlowEventSupplementProvider supplement = supplementCalls(
                new AtomicInteger(), new FlowEventSourceBatch(
                        "aktools", List.of(supplementRecord),
                        RiskDataQualityStatus.AVAILABLE,
                        null, null, LocalDate.of(2026, 7, 1),
                        true, FETCHED_AT.plusMinutes(1), null));
        CompositeFlowEventSourceClient client =
                new CompositeFlowEventSourceClient(
                        request -> primary, List.of(supplement));

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.EARNINGS_FORECAST, object));

        assertThat(result.records()).containsExactly(primaryRecord);
    }

    @Test
    void blockedDatasetNeverFallsThroughToSupplements() {
        AtomicInteger supplementCalls = new AtomicInteger();
        CompositeFlowEventSourceClient client =
                new CompositeFlowEventSourceClient(
                        request -> FlowEventSourceBatch.unavailable(
                                "tushare",
                                "stock_announcement is not supported",
                                FETCHED_AT),
                        List.of(supplementCalls(
                                supplementCalls,
                                FlowEventSourceBatch.validZero(
                                        "aktools/cninfo",
                                        "announcement:2026-07-18",
                                        FETCHED_AT))),
                        Map.of(),
                        Set.of(FlowEventDataset
                                .STOCK_ANNOUNCEMENT.code()));

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.STOCK_ANNOUNCEMENT,
                new RiskObjectKey(
                        RiskObjectType.STOCK, "600519.SH")));

        assertThat(result.qualityStatus())
                .isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(result.source()).isEqualTo("tushare");
        assertThat(supplementCalls).hasValue(0);
    }

    private static FlowEventSupplementProvider supplementCalls(
            AtomicInteger calls,
            FlowEventSourceBatch batch
    ) {
        return new FlowEventSupplementProvider() {
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
            public FlowEventSourceBatch fetch(
                    FlowEventSourceRequest request
            ) {
                calls.incrementAndGet();
                return batch;
            }
        };
    }

    private static FlowEventSourceRecord forecastRecord(
            String recordId,
            RiskObjectKey object,
            LocalDateTime availableAt
    ) {
        LocalDate announcementDate = LocalDate.of(2026, 7, 16);
        return new FlowEventSourceRecord(
                recordId, availableAt + "|" + recordId, object,
                announcementDate, announcementDate.atStartOfDay(),
                announcementDate.atStartOfDay(), availableAt,
                BigDecimal.valueOf(-30), "percent",
                "forecast_change", "预减",
                Map.of(
                        "reportPeriod", LocalDate.of(2026, 6, 30),
                        "forecastType", "预减"));
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
