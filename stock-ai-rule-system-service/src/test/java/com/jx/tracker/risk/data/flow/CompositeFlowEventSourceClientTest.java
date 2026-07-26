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
    void completeEventSupplementReplacesPartialPrimary() {
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

        assertThat(result.records()).containsExactly(supplementRecord);
        assertThat(result.source()).isEqualTo("aktools");
        assertThat(result.historyComplete()).isTrue();
        assertThat(result.fallbackReason())
                .contains("forecast history is partial");
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

    @Test
    void completeAkToolsEventReplacementPreservesItsPublishedUnit() {
        RiskObjectKey object = new RiskObjectKey(
                RiskObjectType.STOCK, "600519.SH");
        LocalDate eventDate = LocalDate.of(2026, 7, 14);
        LocalDateTime observedAt =
                LocalDateTime.of(2026, 7, 15, 0, 0);
        FlowEventSourceRecord primaryRecord =
                new FlowEventSourceRecord(
                        "tushare-reduction", "primary-cursor",
                        object, eventDate,
                        eventDate.atTime(15, 0), observedAt,
                        observedAt.plusDays(1),
                        new BigDecimal("30000"), "shares",
                        "share_reduction", "股东减持",
                        Map.of("direction", "DE"));
        FlowEventSourceRecord supplementRecord =
                new FlowEventSourceRecord(
                        "aktools-reduction", "fallback-cursor",
                        object, eventDate,
                        eventDate.atTime(15, 0), observedAt,
                        observedAt.plusDays(2),
                        new BigDecimal("3"), "tenThousandShares",
                        "share_reduction", "减持",
                        Map.of("direction", "减持"));
        FlowEventSourceBatch primary = new FlowEventSourceBatch(
                "tushare", List.of(primaryRecord),
                RiskDataQualityStatus.AVAILABLE,
                "partial", null, eventDate, false,
                FETCHED_AT, null);
        FlowEventSourceBatch supplement =
                new FlowEventSourceBatch(
                        "aktools", List.of(supplementRecord),
                        RiskDataQualityStatus.AVAILABLE,
                        null, null, eventDate, true,
                        FETCHED_AT.plusMinutes(1), null);
        CompositeFlowEventSourceClient client =
                new CompositeFlowEventSourceClient(
                        request -> primary,
                        List.of(supplementCalls(
                                new AtomicInteger(), supplement)));

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.SHARE_REDUCTION, object));

        assertThat(result.records()).containsExactly(supplementRecord);
        assertThat(result.records().getFirst().unit())
                .isEqualTo("tenThousandShares");
        assertThat(result.records().getFirst().value())
                .isEqualByComparingTo("3");
        assertThat(result.fallbackReason()).contains("partial");
    }

    @Test
    void partialSourcesKeepSameDaySameValueDifferentShareholders() {
        RiskObjectKey object = new RiskObjectKey(
                RiskObjectType.STOCK, "600519.SH");
        FlowEventSourceRecord shareholderA =
                reductionRecord("tushare-a", object, "股东甲");
        FlowEventSourceRecord shareholderB =
                reductionRecord("aktools-b", object, "股东乙");
        CompositeFlowEventSourceClient client =
                partialEventClient(shareholderA, shareholderB);

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.SHARE_REDUCTION, object));

        assertThat(result.records())
                .containsExactly(shareholderA, shareholderB);
        assertThat(result.historyComplete()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.failureReason())
                .contains("primary partial")
                .contains("supplement partial");
    }

    @Test
    void partialSourcesDeduplicateOnlyWhenCommonIdentityMatches() {
        RiskObjectKey object = new RiskObjectKey(
                RiskObjectType.STOCK, "600519.SH");
        FlowEventSourceRecord primaryRecord =
                reductionRecord("tushare-a", object, "股东甲");
        FlowEventSourceRecord duplicate =
                reductionRecord("aktools-a", object, "股东甲");
        CompositeFlowEventSourceClient client =
                partialEventClient(primaryRecord, duplicate);

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.SHARE_REDUCTION, object));

        assertThat(result.records()).containsExactly(primaryRecord);
        assertThat(result.historyComplete()).isFalse();
    }

    @Test
    void partialSourcesPreserveBothRecordsWhenIdentityIsMissing() {
        RiskObjectKey object = new RiskObjectKey(
                RiskObjectType.STOCK, "600519.SH");
        FlowEventSourceRecord primaryRecord =
                reductionRecord("tushare-unknown", object, null);
        FlowEventSourceRecord supplementRecord =
                reductionRecord("aktools-unknown", object, null);
        CompositeFlowEventSourceClient client =
                partialEventClient(
                        primaryRecord, supplementRecord);

        FlowEventSourceBatch result = client.fetch(request(
                FlowEventDataset.SHARE_REDUCTION, object));

        assertThat(result.records())
                .containsExactly(primaryRecord, supplementRecord);
        assertThat(result.historyComplete()).isFalse();
    }

    private static CompositeFlowEventSourceClient partialEventClient(
            FlowEventSourceRecord primaryRecord,
            FlowEventSourceRecord supplementRecord
    ) {
        FlowEventSourceBatch primary = new FlowEventSourceBatch(
                "tushare", List.of(primaryRecord),
                RiskDataQualityStatus.AVAILABLE,
                "primary partial", null,
                primaryRecord.tradeDate(), false,
                FETCHED_AT, null);
        FlowEventSourceBatch supplement =
                new FlowEventSourceBatch(
                        "aktools", List.of(supplementRecord),
                        RiskDataQualityStatus.AVAILABLE,
                        "supplement partial", null,
                        supplementRecord.tradeDate(), false,
                        FETCHED_AT.plusMinutes(1), null);
        return new CompositeFlowEventSourceClient(
                request -> primary,
                List.of(supplementCalls(
                        new AtomicInteger(), supplement)));
    }

    private static FlowEventSourceRecord reductionRecord(
            String recordId,
            RiskObjectKey object,
            String shareholder
    ) {
        LocalDate eventDate = LocalDate.of(2026, 7, 14);
        LocalDateTime observedAt =
                LocalDateTime.of(2026, 7, 15, 0, 0);
        return new FlowEventSourceRecord(
                recordId, recordId + "-cursor", object,
                eventDate, eventDate.atTime(15, 0), observedAt,
                observedAt.plusDays(1),
                new BigDecimal("30000"), "shares",
                "share_reduction", "股东减持",
                shareholder == null
                        ? Map.of("direction", "DE")
                        : Map.of(
                                "direction", "DE",
                                "shareholder", shareholder));
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
                recordId.startsWith("tushare")
                        ? Map.of(
                                "reportPeriod",
                                LocalDate.of(2026, 6, 30),
                                "forecastType", "预减")
                        : Map.of(
                                "predictionMetric", "净利润",
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
