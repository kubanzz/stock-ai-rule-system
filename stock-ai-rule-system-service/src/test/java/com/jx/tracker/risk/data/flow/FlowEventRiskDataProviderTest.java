package com.jx.tracker.risk.data.flow;

import com.jx.tracker.risk.data.event.EconomicMeaning;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlowEventRiskDataProviderTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private static final RiskObjectKey OTHER_STOCK = new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ");
    private static final LocalDate START = LocalDate.of(2021, 7, 18);
    private static final LocalDate END = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 7, 18, 15, 0);
    private static final LocalDateTime AVAILABLE_AT = OBSERVED_AT.plusHours(1);

    @Test
    void supportsAllSixDatasetsAndMapsThreeHorizons() {
        RecordingClient client = available("aktools", recordFor(MARKET,
                "margin-1", new BigDecimal("123.45"), "balance", Map.of("previousBalance", "130.00")));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(client);

        assertThat(FlowEventDataset.codes()).containsExactlyInAnyOrder(
                "margin_financing", "etf_fund_flow", "earnings_forecast",
                "stock_announcement", "share_unlock", "share_reduction");
        assertThat(FlowEventDataset.codes()).allMatch(provider::supports);

        RiskProviderBatch batch = provider.fetch("margin_financing", requestFor(MARKET, List.of(
                RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM), null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.observations())
                .extracting(observation -> observation.horizon())
                .containsExactlyInAnyOrder(
                        RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM,
                        RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM);
        assertThat(batch.observations()).extracting(observation -> observation.indicatorCode())
                .containsOnly("V5", "A1");
    }

    @Test
    void distinguishesSuccessfulEmptyQueryFromSourceFailure() {
        FlowEventRiskDataProvider emptyProvider = new FlowEventRiskDataProvider(
                request -> FlowEventSourceBatch.validZero("aktools", "empty-1", AVAILABLE_AT));
        FlowEventRiskDataProvider failedProvider = new FlowEventRiskDataProvider(
                request -> FlowEventSourceBatch.unavailable("aktools", "source timeout", AVAILABLE_AT));

        RiskProviderBatch empty = emptyProvider.fetch(
                "stock_announcement", request(List.of(RiskHorizon.SHORT_TERM), null));
        assertThat(empty.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(empty.observations()).hasSize(4).allSatisfy(observation -> {
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
            assertThat(observation.value()).isZero();
            assertThat(observation.attributes())
                    .containsEntry("validZeroAudit", true)
                    .containsEntry("datasetCode", "stock_announcement");
        });
        RiskProviderBatch failed = failedProvider.fetch(
                "stock_announcement", request(List.of(RiskHorizon.SHORT_TERM), null));
        assertThat(failed.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(failed.errorMessage()).contains("source timeout");
    }

    @Test
    void mixedSuccessfulEventBatchAuditsCurrentDateZerosForMissingObjectsAndIndicators() {
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools",
                record("notice-trust", new BigDecimal("70"), "notice",
                        Map.of("economicMeaning", "market_trust", "adverse", true))));
        RiskProviderRequest request = new RiskProviderRequest(
                List.of(STOCK, OTHER_STOCK), List.of(RiskHorizon.SHORT_TERM), START, END, null);

        RiskProviderBatch batch = provider.fetch("stock_announcement", request);

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.observations()).hasSize(8);
        assertThat(batch.observations()).filteredOn(item -> item.object().equals(STOCK))
                .hasSize(4)
                .filteredOn(item -> item.indicatorCode().equals("T4"))
                .singleElement().satisfies(item -> {
                    assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
                    assertThat(item.attributes())
                            .containsEntry("alreadyNormalizedRiskScore", true)
                            .containsEntry("normalizationContract", "direct-0-100-v1");
                });
        assertThat(batch.observations()).filteredOn(item ->
                        item.object().equals(OTHER_STOCK)
                                || !item.indicatorCode().equals("T4"))
                .allSatisfy(item -> {
                    assertThat(item.tradeDate()).isEqualTo(END);
                    assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
                    assertThat(item.attributes())
                            .containsEntry("validZeroAudit", true)
                            .containsEntry("noEvent", true);
                });
    }

    @Test
    void incompleteEventHistoryDoesNotSynthesizeZerosOrInflateCoverage() {
        FlowEventSourceBatch incomplete = new FlowEventSourceBatch(
                "aktools",
                List.of(record("notice-trust", new BigDecimal("70"), "notice",
                        Map.of("economicMeaning", "market_trust", "adverse", true))),
                RiskDataQualityStatus.AVAILABLE, "recent-only", "cursor-1",
                LocalDate.of(2025, 1, 1), false, AVAILABLE_AT, null);
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(request -> incomplete);

        FlowEventFetchResult result = provider.fetchWithCoverage(
                "stock_announcement", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(result.batch().qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.batch().nextCheckpoint()).isNull();
        assertThat(result.batch().observations()).singleElement().satisfies(item -> {
            assertThat(item.indicatorCode()).isEqualTo("T4");
            assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(item.value()).isNull();
            assertThat(item.attributes())
                    .containsEntry("sourceQuality", "available")
                    .containsEntry("partialHistoryReason", "recent-only");
            assertThat((BigDecimal) item.attributes().get("auditValue"))
                    .isEqualByComparingTo("70");
        });
        assertThat(result.batch().events()).singleElement().satisfies(item -> {
            assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(item.payload())
                    .containsEntry("sourceQuality", "available")
                    .containsEntry("partialHistoryReason", "recent-only");
        });
        assertThat(result.coverageReport().indicators()).containsExactly(
                new IndicatorCoverage("T1", new BigDecimal("30"), 0, 0, 0, 1),
                new IndicatorCoverage("T2", new BigDecimal("25"), 0, 0, 0, 1),
                new IndicatorCoverage("T3", new BigDecimal("20"), 0, 0, 0, 1),
                new IndicatorCoverage("T4", new BigDecimal("25"), 0, 0, 0, 1));
        assertThat(result.coverageReport().weightedCoverage()).isEqualByComparingTo("0.0000");
    }

    @Test
    void incompleteEmptyEventQueryIsInsufficientInsteadOfValidZero() {
        FlowEventSourceBatch incompleteEmpty = new FlowEventSourceBatch(
                "aktools", List.of(), RiskDataQualityStatus.VALID_ZERO,
                "event history incomplete", "cursor-1", LocalDate.of(2025, 1, 1),
                false, AVAILABLE_AT, null);
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(request -> incompleteEmpty);

        FlowEventFetchResult result = provider.fetchWithCoverage(
                "stock_announcement", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(result.batch().qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.batch().observations()).isEmpty();
        assertThat(result.batch().errorMessage()).contains("event history incomplete");
        assertThat(result.coverageReport().indicators()).allSatisfy(item -> {
            assertThat(item.validZeroCount()).isZero();
            assertThat(item.insufficientHistoryCount()).isEqualTo(1);
        });
        assertThat(result.coverageReport().weightedCoverage()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void supplementIsUsedOnlyForUnavailableOrInsufficientPrimaryAndAuditsReason() {
        FlowEventSourceClient primary = request -> FlowEventSourceBatch.insufficientHistory(
                "aktools", "only recent history", LocalDate.of(2025, 1, 1), AVAILABLE_AT);
        FlowEventSupplementProvider supplement = new StubSupplement(
                "licensed-supplement", available("licensed-supplement", record(
                "forecast-1", new BigDecimal("-35"), "forecast_change",
                Map.of("economicMeaning", "cash_flow"))).fetch(null));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(
                new CompositeFlowEventSourceClient(primary, List.of(supplement)));

        FlowEventFetchResult result = provider.fetchWithCoverage(
                "earnings_forecast", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(result.batch().source()).isEqualTo("licensed-supplement");
        assertThat(result.batch().observations()).singleElement()
                .satisfies(observation -> {
                    assertThat(observation.indicatorCode()).isEqualTo("T1");
                    assertThat(observation.attributes()).containsEntry("fallbackReason", "only recent history");
                });
        assertThat(result.coverageReport().fallbackReason()).isEqualTo("only recent history");
    }

    @Test
    void incompleteAvailablePrimaryAlsoTriggersSupplementAndMergesRecords() {
        FlowEventSourceBatch primaryBatch = new FlowEventSourceBatch(
                "aktools", List.of(record("forecast-primary", new BigDecimal("-20"),
                "forecast_change", Map.of("economicMeaning", "cash_flow", "adverse", true))),
                RiskDataQualityStatus.AVAILABLE, "recent-only", "cursor-1",
                LocalDate.of(2025, 1, 1), false, AVAILABLE_AT, null);
        FlowEventSourceBatch supplementBatch = new FlowEventSourceBatch(
                "licensed", List.of(record("forecast-history", new BigDecimal("-30"),
                "forecast_change", Map.of("economicMeaning", "cash_flow", "adverse", true))),
                RiskDataQualityStatus.AVAILABLE, null, "cursor-2",
                START, true, AVAILABLE_AT.plusMinutes(1), null);
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(
                new CompositeFlowEventSourceClient(request -> primaryBatch,
                        List.of(new StubSupplement("licensed", supplementBatch))));

        FlowEventFetchResult result = provider.fetchWithCoverage(
                "earnings_forecast", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(result.batch().observations()).hasSize(2);
        assertThat(result.batch().observations()).extracting(observation ->
                observation.attributes().get("sourceRecordId"))
                .containsExactlyInAnyOrder("forecast-primary", "forecast-history");
        assertThat(result.coverageReport().fallbackReason()).contains("recent-only");
        assertThat(result.coverageReport().historyGaps()).isEmpty();
    }

    @Test
    void twoFailedSourcesRemainUnavailable() {
        FlowEventSourceClient primary = request -> FlowEventSourceBatch.unavailable(
                "aktools", "primary timeout", AVAILABLE_AT);
        FlowEventSupplementProvider supplement = new StubSupplement(
                "supplement", FlowEventSourceBatch.unavailable("supplement", "supplement timeout", AVAILABLE_AT));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(
                new CompositeFlowEventSourceClient(primary, List.of(supplement)));

        RiskProviderBatch batch = provider.fetch("share_reduction", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(batch.errorMessage()).contains("primary timeout").contains("supplement timeout");
    }

    @Test
    void reportsFiveYearHistoryGapWithoutSynthesizingObservations() {
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(
                request -> FlowEventSourceBatch.insufficientHistory(
                        "aktools", "ETF endpoint only exposes recent history",
                        LocalDate.of(2025, 1, 1), AVAILABLE_AT));

        FlowEventFetchResult result = provider.fetchWithCoverage(
                "etf_fund_flow", requestFor(MARKET, List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(result.batch().qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.batch().observations()).isEmpty();
        assertThat(result.coverageReport().earliestAvailableDate()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(result.coverageReport().historyGaps()).singleElement()
                .asString().contains("2021-07-18").contains("2024-12-31");
        assertThat(result.coverageReport().weightedCoverage()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.coverageReport().indicators()).singleElement()
                .satisfies(item -> assertThat(item.insufficientHistoryCount()).isEqualTo(1));
    }

    @Test
    void currentEtfOrderFlowProxyNeverBecomesFormalA2Evidence() {
        FlowEventSourceRecord proxy = recordFor(
                MARKET, "etf-proxy", new BigDecimal("-2000000"), "etf_order_flow_proxy",
                Map.of(
                        "referenceAssets", new BigDecimal("100000000"),
                        "proxy", true,
                        "proxyType", "secondaryMarketOrderFlow"));
        FlowEventSourceBatch partial = new FlowEventSourceBatch(
                "aktools", List.of(proxy), RiskDataQualityStatus.AVAILABLE,
                "current order-flow proxy is not ETF redemption history", null,
                END, false, AVAILABLE_AT, null);
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(request -> partial);

        RiskProviderBatch batch = provider.fetch(
                "etf_fund_flow", requestFor(MARKET, List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(batch.observations()).extracting(observation -> observation.indicatorCode())
                .doesNotContain("A2");
        assertThat(batch.nextCheckpoint()).isNull();
    }

    @Test
    void recentOnlyReductionPersistsCurrentEventButRemainsInsufficientWithoutCheckpoint() {
        FlowEventSourceRecord currentReduction = record(
                "reduction-current", new BigDecimal("20000"), "share_reduction",
                Map.of(
                        "actualReduction", true,
                        "adverse", true,
                        "modifierRatio", new BigDecimal("0.12")));
        FlowEventSourceBatch partial = new FlowEventSourceBatch(
                "aktools", List.of(currentReduction), RiskDataQualityStatus.AVAILABLE,
                "share reduction endpoint exposes only recent history", null,
                LocalDate.of(2026, 1, 1), false, AVAILABLE_AT, null);
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(request -> partial);

        RiskProviderBatch batch = provider.fetch(
                "share_reduction", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(batch.errorMessage()).contains("only recent history");
        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(event.payload())
                    .containsEntry("sourceRecordId", "reduction-current")
                    .containsEntry("sourceQuality", "available")
                    .containsKey("partialHistoryReason");
        });
        assertThat(batch.nextCheckpoint()).isNull();
    }

    @Test
    void filtersRecordsThatWereNotAvailableByRequestedEndDate() {
        FlowEventSourceRecord future = new FlowEventSourceRecord(
                "future-1", "cursor-2", STOCK, END.plusDays(1), END.plusDays(1).atStartOfDay(),
                OBSERVED_AT, END.plusDays(1).atTime(9, 0), new BigDecimal("90"), "score",
                "forecast_change", "未来才发布的预告", Map.of("economicMeaning", "cash_flow"));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools", future));

        RiskProviderBatch batch = provider.fetch(
                "earnings_forecast", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(batch.observations()).singleElement()
                .satisfies(item -> assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO));
        assertThat(batch.events()).isEmpty();
    }

    @Test
    void filtersRecordsOutsideRequestedObjectsAndFactDates() {
        FlowEventSourceRecord otherObject = recordFor(OTHER_STOCK,
                "forecast-other", new BigDecimal("-35"), "forecast_change",
                Map.of("economicMeaning", "cash_flow", "adverse", true));
        FlowEventSourceRecord oldFact = new FlowEventSourceRecord(
                "forecast-old", "cursor-old", STOCK, START.minusDays(1), START.minusDays(1).atStartOfDay(),
                START.atStartOfDay(), START.plusDays(1).atStartOfDay(), new BigDecimal("-35"), "percent",
                "forecast_change", "历史预告", Map.of("economicMeaning", "cash_flow", "adverse", true));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(
                available("aktools", otherObject, oldFact));

        RiskProviderBatch batch = provider.fetch(
                "earnings_forecast", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(batch.observations()).singleElement()
                .satisfies(item -> assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO));
        assertThat(batch.events()).isEmpty();
    }

    @Test
    void rejectsDatasetObjectLevelMismatchBeforeCallingSource() {
        RecordingClient client = available("aktools", record(
                "margin-stock", new BigDecimal("100"), "balance", Map.of("previousBalance", "110")));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(client);

        assertThatThrownBy(() -> provider.fetch(
                "margin_financing", request(List.of(RiskHorizon.SHORT_TERM), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("margin_financing")
                .hasMessageContaining("market:CN-A");
        assertThat(client.requests).isEmpty();
    }

    @Test
    void rejectsUnnormalizedStockObjectBeforeCallingSource() {
        RecordingClient client = available("aktools", record(
                "forecast", new BigDecimal("-20"), "forecast_change", Map.of("adverse", true)));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(client);
        RiskProviderRequest request = new RiskProviderRequest(
                List.of(new RiskObjectKey(RiskObjectType.STOCK, "600519")),
                List.of(RiskHorizon.SHORT_TERM), START, END, null);

        assertThatThrownBy(() -> provider.fetch("earnings_forecast", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalized stock codes");
        assertThat(client.requests).isEmpty();
    }

    @Test
    void plannedUnlockIsModifierCandidateAndDoesNotBecomeDimensionScore() {
        FlowEventSourceRecord plannedUnlock = new FlowEventSourceRecord(
                "unlock-plan", "cursor-3", STOCK, END.plusDays(30), END.plusDays(30).atTime(9, 30),
                OBSERVED_AT, AVAILABLE_AT, new BigDecimal("12.5"), "percent",
                "share_unlock", "限售股计划解禁", Map.of("scheduled", true));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools", plannedUnlock));

        RiskProviderBatch batch = provider.fetch("share_unlock", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.observations()).isEmpty();
        assertThat(batch.events()).singleElement().satisfies(event -> {
            assertThat(event.occurredAt()).isAfter(event.observedAt());
            assertThat(event.dimension()).isEqualTo(RiskDimension.FORCED_SELLING);
            assertThat(event.severityScore()).isNull();
            assertThat(event.payload())
                    .containsEntry("modifierCandidate", true)
                    .containsEntry("dimensionScoreEligible", false)
                    .containsEntry("confirmed", false);
        });
    }

    @Test
    void actualReductionRequiresMoneyOrPriceConfirmation() {
        FlowEventSourceRecord unconfirmed = record(
                "reduce-1", new BigDecimal("8"), "share_reduction",
                Map.of("actualReduction", true, "modifierRatio", new BigDecimal("8")));
        FlowEventSourceRecord confirmed = record(
                "reduce-2", new BigDecimal("9"), "share_reduction",
                Map.of(
                        "actualReduction", true,
                        "fundFlowConfirmed", true,
                        "modifierRatio", new BigDecimal("9")));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools", unconfirmed, confirmed));

        RiskProviderBatch batch = provider.fetch("share_reduction", request(List.of(RiskHorizon.SHORT_TERM), null));

        assertThat(batch.events()).hasSize(2);
        assertThat(batch.events()).filteredOn(event ->
                        "reduce-1".equals(event.payload().get("sourceRecordId")))
                .singleElement().satisfies(event -> {
                    assertThat(event.payload())
                            .containsEntry("confirmed", false)
                            .containsEntry("priceConfirmed", false)
                            .containsEntry("fundFlowConfirmed", false)
                            .containsEntry("modifierSeverity", new BigDecimal("8"))
                            .containsEntry("confirmationContract", "pit-price-fund-evidence-v1");
                    assertThat(event.severityScore()).isNull();
                });
        assertThat(batch.events()).filteredOn(event ->
                        "reduce-2".equals(event.payload().get("sourceRecordId")))
                .singleElement().satisfies(event -> {
                    assertThat(event.payload())
                            .containsEntry("confirmed", true)
                            .containsEntry("priceConfirmed", false)
                            .containsEntry("fundFlowConfirmed", true)
                            .doesNotContainKeys("probability", "crashProbability");
                    assertThat(event.severityScore()).isEqualByComparingTo("9");
                });
        assertThat(batch.observations()).isEmpty();
    }

    @Test
    void passesCheckpointAndRangeAndReturnsStableDeduplicatedKeys() {
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "stock_announcement", "stock:600519.SH", "cursor-0", OBSERVED_AT);
        FlowEventSourceRecord duplicate = record(
                "notice-1", new BigDecimal("75"), "notice",
                Map.of("economicMeaning", EconomicMeaning.MARKET_TRUST.code()));
        RecordingClient client = available("aktools", duplicate, duplicate);
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(client);

        RiskProviderBatch first = provider.fetch(
                "stock_announcement", request(List.of(RiskHorizon.SHORT_TERM), checkpoint));
        RiskProviderBatch rerun = provider.fetch(
                "stock_announcement", request(List.of(RiskHorizon.SHORT_TERM), first.nextCheckpoint()));

        assertThat(client.requests).allSatisfy(sourceRequest -> {
            assertThat(sourceRequest.startDate()).isEqualTo(START);
            assertThat(sourceRequest.endDate()).isEqualTo(END);
        });
        assertThat(client.requests).extracting(FlowEventSourceRequest::checkpoint)
                .containsExactly(checkpoint, first.nextCheckpoint());
        assertThat(first.events()).hasSize(1);
        assertThat(rerun.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(rerun.events()).isEmpty();
        assertThat(first.nextCheckpoint().cursor()).isEqualTo("cursor-1");
    }

    @Test
    void rejectsCheckpointForAnotherDatasetOrScope() {
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(
                request -> FlowEventSourceBatch.validZero("aktools", "cursor-1", AVAILABLE_AT));
        RiskIngestionCheckpoint wrongDataset = new RiskIngestionCheckpoint(
                "share_unlock", "stock:600519.SH", "cursor-0", OBSERVED_AT);
        RiskIngestionCheckpoint wrongScope = new RiskIngestionCheckpoint(
                "stock_announcement", "stock:000001.SZ", "cursor-0", OBSERVED_AT);

        assertThatThrownBy(() -> provider.fetch("stock_announcement",
                request(List.of(RiskHorizon.SHORT_TERM), wrongDataset)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dataset");
        assertThatThrownBy(() -> provider.fetch("stock_announcement",
                request(List.of(RiskHorizon.SHORT_TERM), wrongScope)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void requiresStrictlyOrderedCheckpointBoundary() {
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "stock_announcement", "stock:600519.SH", "cursor-5", OBSERVED_AT);
        FlowEventSourceRecord before = new FlowEventSourceRecord(
                "notice-before", "cursor-4", STOCK, END, END.atStartOfDay(),
                OBSERVED_AT, AVAILABLE_AT, new BigDecimal("50"), "score", "notice", "公告",
                Map.of("economicMeaning", "market_trust", "adverse", true));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools", before));

        RiskProviderBatch batch = provider.fetch("stock_announcement",
                request(List.of(RiskHorizon.SHORT_TERM), checkpoint));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(batch.nextCheckpoint()).isEqualTo(checkpoint);
    }

    @Test
    void coverageCountsActualIndicatorsOnceAcrossHorizonsWithCatalogWeights() {
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools",
                recordFor(MARKET, "margin-coverage", new BigDecimal("90"), "balance",
                        Map.of("previousBalance", "100", "referenceBalance", "120"))));

        FlowEventCoverageReport coverage = provider.fetchWithCoverage(
                "margin_financing", requestFor(MARKET,
                        List.of(RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM), null))
                .coverageReport();

        assertThat(coverage.indicators()).containsExactly(
                new IndicatorCoverage("V5", new BigDecimal("15"), 1, 0, 0, 0),
                new IndicatorCoverage("A1", new BigDecimal("25"), 1, 0, 0, 0));
        assertThat(coverage.weightedCoverage()).isEqualByComparingTo("1");
    }

    @Test
    void coverageSeparatesValidZeroAndInsufficientIndicators() {
        FlowEventSourceRecord record = recordFor(MARKET, "margin-first", new BigDecimal("100"),
                "balance", Map.of("referenceBalance", "100"));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools", record));

        FlowEventCoverageReport coverage = provider.fetchWithCoverage(
                "margin_financing", requestFor(MARKET, List.of(RiskHorizon.SHORT_TERM), null))
                .coverageReport();

        assertThat(coverage.indicators()).containsExactly(
                new IndicatorCoverage("V5", new BigDecimal("15"), 1, 0, 0, 0),
                new IndicatorCoverage("A1", new BigDecimal("25"), 0, 0, 0, 1));
        assertThat(coverage.weightedCoverage()).isEqualByComparingTo("0.3750");
    }

    @Test
    void tCoverageCountsObservationNotEventOrHorizonDuplicates() {
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools",
                record("notice-trust", new BigDecimal("70"), "notice",
                        Map.of("economicMeaning", "market_trust", "adverse", true))));

        FlowEventCoverageReport coverage = provider.fetchWithCoverage(
                "stock_announcement", request(List.of(
                        RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM), null))
                .coverageReport();

        assertThat(coverage.indicators()).containsExactly(
                new IndicatorCoverage("T1", new BigDecimal("30"), 0, 1, 0, 0),
                new IndicatorCoverage("T2", new BigDecimal("25"), 0, 1, 0, 0),
                new IndicatorCoverage("T3", new BigDecimal("20"), 0, 1, 0, 0),
                new IndicatorCoverage("T4", new BigDecimal("25"), 1, 0, 0, 0));
        assertThat(coverage.weightedCoverage()).isEqualByComparingTo("1.0000");
    }

    @Test
    void rejectedEventCoverageDoesNotRepeatPerHorizon() {
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(available("aktools",
                record("notice-other", null, "notice", Map.of("announcementCategory", "other"))));

        FlowEventCoverageReport coverage = provider.fetchWithCoverage(
                "stock_announcement", request(List.of(
                        RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM), null))
                .coverageReport();

        assertThat(coverage.indicators()).allSatisfy(item -> assertThat(item.failedCount()).isEqualTo(1));
        assertThat(coverage.weightedCoverage()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static RiskProviderRequest request(
            List<RiskHorizon> horizons,
            RiskIngestionCheckpoint checkpoint
    ) {
        return new RiskProviderRequest(List.of(STOCK), horizons, START, END, checkpoint);
    }

    private static RiskProviderRequest requestFor(
            RiskObjectKey object,
            List<RiskHorizon> horizons,
            RiskIngestionCheckpoint checkpoint
    ) {
        return new RiskProviderRequest(List.of(object), horizons, START, END, checkpoint);
    }

    private static RecordingClient available(String source, FlowEventSourceRecord... records) {
        return new RecordingClient(new FlowEventSourceBatch(
                source, List.of(records), RiskDataQualityStatus.AVAILABLE, null,
                "cursor-1", null, true, AVAILABLE_AT, null));
    }

    private static FlowEventSourceRecord record(
            String id,
            BigDecimal value,
            String eventCode,
            Map<String, Object> attributes
    ) {
        return recordFor(STOCK, id, value, eventCode, attributes);
    }

    private static FlowEventSourceRecord recordFor(
            RiskObjectKey object,
            String id,
            BigDecimal value,
            String eventCode,
            Map<String, Object> attributes
    ) {
        return new FlowEventSourceRecord(
                id, "cursor-1", object, END, END.atTime(15, 0), OBSERVED_AT, AVAILABLE_AT,
                value, "ratio", eventCode, id, attributes);
    }

    private static final class RecordingClient implements FlowEventSourceClient {
        private final FlowEventSourceBatch response;
        private final List<FlowEventSourceRequest> requests = new ArrayList<>();

        private RecordingClient(FlowEventSourceBatch response) {
            this.response = response;
        }

        @Override
        public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
            requests.add(request);
            return response;
        }
    }

    private record StubSupplement(String providerCode, FlowEventSourceBatch response)
            implements FlowEventSupplementProvider {
        @Override
        public int priority() {
            return 10;
        }

        @Override
        public boolean supports(String datasetCode) {
            return true;
        }

        @Override
        public FlowEventSourceBatch fetch(FlowEventSourceRequest request) {
            return response;
        }
    }
}
