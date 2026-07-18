package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class MarketRiskDataProviderTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final LocalDate END_DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime FETCHED_AT = LocalDateTime.of(2026, 7, 18, 18, 0);

    @Test
    void supportsSixDatasetsAndReturnsValidZeroForSuccessfulEmptyResponse() {
        MarketRiskDataProvider provider = new MarketRiskDataProvider((dataset, request) ->
                new MarketSourceBatch("fixed", List.of(), request.checkpoint(), FETCHED_AT));

        assertThat(EnumSet.allOf(MarketDatasetCode.class))
                .allMatch(dataset -> provider.supports(dataset.code()));
        RiskProviderBatch emptyBatch = provider.fetch("breadth", request(null));
        assertThat(emptyBatch.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(emptyBatch.industryExposures()).isEmpty();
        assertThat(provider.coverageReport(
                "breadth", emptyBatch, MARKET, RiskHorizon.SHORT_TERM, END_DATE, END_DATE.atTime(23, 59, 59)
        ).items()).hasSize(2)
                .allSatisfy(item -> assertThat(item.qualityStatus())
                        .isEqualTo(RiskDataQualityStatus.VALID_ZERO));
        assertThat(provider.supportedIndicatorCodes())
                .containsExactlyInAnyOrder(
                        "V1", "V3", "V4", "S1", "S2", "S4", "C1", "C2", "C3", "C4", "C5",
                        "A3", "A4", "A5"
                );
    }

    @Test
    void marketIndicatorCatalogKeepsFrameworkWeightsForStaticIntegrationCoverage() {
        assertThat(MarketRiskIndicator.A3.weight()).isEqualByComparingTo("15");
        assertThat(MarketRiskIndicator.A4.weight()).isEqualByComparingTo("20");
        assertThat(MarketRiskIndicator.A5.weight()).isEqualByComparingTo("20");

        Map<RiskDimension, BigDecimal> supportedWeights = Arrays.stream(MarketRiskIndicator.values())
                .collect(Collectors.groupingBy(
                        MarketRiskIndicator::dimension,
                        Collectors.reducing(BigDecimal.ZERO, MarketRiskIndicator::weight, BigDecimal::add)
                ));
        assertThat(supportedWeights).containsEntry(RiskDimension.STRUCTURAL_FRAGILITY, new BigDecimal("55"))
                .containsEntry(RiskDimension.EXTERNAL_TRANSMISSION, new BigDecimal("70"))
                .containsEntry(RiskDimension.LOCAL_CONFIRMATION, new BigDecimal("85"))
                .containsEntry(RiskDimension.FORCED_SELLING, new BigDecimal("55"));
    }

    @Test
    void turnsSourceFailureIntoUnavailableWithoutLeakingExceptionType() {
        MarketRiskDataProvider provider = new MarketRiskDataProvider((dataset, request) -> {
            throw new IllegalStateException("upstream timeout");
        });

        RiskProviderBatch batch = provider.fetch("valuation", request(null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(batch.errorMessage()).isEqualTo("upstream timeout");
        assertThat(batch.observations()).isEmpty();
        assertThat(batch.industryExposures()).isEmpty();
    }

    @Test
    void valuationFiltersFutureTradeAndAvailabilityAndPassesCheckpointThrough() {
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "valuation", "CN-A", "cursor-17", LocalDateTime.of(2026, 7, 17, 18, 0)
        );
        List<MarketSourceRecord> records = List.of(
                valuation(END_DATE, LocalDateTime.of(2026, 7, 18, 16, 0), "18"),
                valuation(END_DATE, LocalDateTime.of(2026, 7, 18, 16, 0), "18"),
                valuation(END_DATE.plusDays(1), LocalDateTime.of(2026, 7, 18, 16, 0), "99"),
                valuation(END_DATE, LocalDateTime.of(2026, 7, 19, 8, 0), "88")
        );
        MarketRiskDataProvider provider = provider(records, checkpoint);

        RiskProviderBatch first = provider.fetch("valuation", request(checkpoint));
        RiskProviderBatch second = provider.fetch("valuation", request(checkpoint));

        assertThat(first.nextCheckpoint()).isEqualTo(checkpoint);
        assertThat(first.observations()).extracting(RiskObservation::value)
                .contains(new BigDecimal("18"), new BigDecimal("0.035"))
                .doesNotContain(new BigDecimal("99"), new BigDecimal("88"));
        assertThat(first.observations()).extracting(o -> o.attributes().get("observationKey"))
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(second.observations().stream()
                        .map(o -> o.attributes().get("observationKey")).toList());
    }

    @Test
    void valuationWithoutRiskFreeYieldKeepsPeButMarksRiskPremiumInsufficient() {
        ValuationPoint partial = new ValuationPoint(
                MARKET, END_DATE, new BigDecimal("18"), new BigDecimal("0.04"), null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch batch = provider(List.of(partial), null).fetch("valuation", request(null));

        assertThat(batch.observations()).anySatisfy(observation -> {
            assertThat(observation.attributes()).containsEntry("metric", "peTtm");
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
            assertThat(observation.value()).isEqualByComparingTo("18");
        }).anySatisfy(observation -> {
            assertThat(observation.attributes()).containsEntry("metric", "riskPremium");
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(observation.value()).isNull();
        });
    }

    @Test
    void dailySeriesProducesDocumentedProxiesAndMarksNewStockHistoryInsufficient() {
        List<MarketSourceRecord> records = dailyPoints(65);
        MarketRiskDataProvider provider = provider(records, null);

        RiskProviderBatch batch = provider.fetch("market_daily", request(null));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.observations()).extracting(RiskObservation::indicatorCode)
                .contains("V3", "V4", "C1", "C3", "C4", "C5", "A3", "A5");
        assertThat(batch.observations())
                .anySatisfy(observation -> {
                    assertThat(observation.horizon()).isEqualTo(RiskHorizon.LONG_TERM);
                    assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                    assertThat(observation.value()).isNull();
                })
                .anySatisfy(observation -> {
                    assertThat(observation.horizon()).isEqualTo(RiskHorizon.SHORT_TERM);
                    assertThat(observation.indicatorCode()).isEqualTo("V3");
                    assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
                    assertThat(observation.attributes()).containsKeys(
                            "window", "contextWindow", "baselineWindow", "metric", "observationKey"
                    );
                })
                .anySatisfy(observation -> {
                    assertThat(observation.horizon()).isEqualTo(RiskHorizon.LONG_TERM);
                    assertThat(observation.indicatorCode()).isEqualTo("A3");
                    assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                    assertThat(observation.value()).isNull();
                });
    }

    @Test
    void dailySeriesProducesThreeHorizonA3AndA5ProxiesWithSourceTimeSemantics() {
        RiskProviderBatch batch = provider(volatileDailyPoints(130), null)
                .fetch("market_daily", request(null));

        List<RiskObservation> latest = batch.observations().stream()
                .filter(observation -> observation.tradeDate().equals(END_DATE))
                .filter(observation -> observation.indicatorCode().equals("A3")
                        || observation.indicatorCode().equals("A5"))
                .toList();
        assertThat(latest).hasSize(6)
                .extracting(RiskObservation::horizon)
                .containsExactlyInAnyOrder(
                        RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM,
                        RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM
                );
        assertThat(latest).allSatisfy(observation -> {
            assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
            assertThat(observation.value()).isNotNull().isNotZero();
            assertThat(observation.observedAt()).isEqualTo(END_DATE.atTime(15, 0));
            assertThat(observation.availableAt()).isEqualTo(END_DATE.atTime(16, 0));
            assertThat(observation.attributes()).containsEntry("proxy", true)
                    .containsKeys("proxyFormula", "observationKey");
        });
        assertThat(latest).filteredOn(observation -> observation.indicatorCode().equals("A3"))
                .allSatisfy(observation -> assertThat(observation.attributes())
                        .containsKeys("trendDistance", "realizedVolatilityRatio"));
        assertThat(provider(volatileDailyPoints(130), null).coverageReport(
                "market_daily", batch, MARKET, RiskHorizon.SHORT_TERM, END_DATE, END_DATE.atTime(23, 59, 59)
        ).supportedIndicatorCodes())
                .contains("A3", "A5");
    }

    @Test
    void a3TreatsFlatRecentReturnsAsInsufficientInsteadOfAvailableZero() {
        RiskProviderBatch batch = provider(flatRecentDailyPoints(25), null)
                .fetch("market_daily", request(null));

        assertThat(batch.observations()).filteredOn(observation ->
                        observation.tradeDate().equals(END_DATE)
                                && observation.horizon() == RiskHorizon.SHORT_TERM
                                && observation.indicatorCode().equals("A3"))
                .singleElement()
                .satisfies(observation -> {
                    assertThat(observation.qualityStatus())
                            .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                    assertThat(observation.value()).isNull();
                });
    }

    @Test
    void a3AndA5RejectStaleHistoryInsteadOfPublishingAvailableValues() {
        List<MarketSourceRecord> records = new ArrayList<>(volatileDailyPoints(30));
        int staleIndex = records.size() - 2;
        MarketDailyPoint original = (MarketDailyPoint) records.get(staleIndex);
        records.set(staleIndex, new MarketDailyPoint(
                original.object(), original.tradeDate(), original.open(), original.close(), original.volume(),
                original.benchmarkClose(), original.leaderClose(), original.observedAt(), original.availableAt(),
                original.source(), RiskDataQualityStatus.STALE
        ));

        RiskProviderBatch batch = provider(records, null).fetch("market_daily", request(null));

        assertThat(batch.observations()).filteredOn(observation ->
                        observation.tradeDate().equals(END_DATE)
                                && observation.horizon() == RiskHorizon.SHORT_TERM
                                && (observation.indicatorCode().equals("A3")
                                || observation.indicatorCode().equals("A5")))
                .hasSize(2)
                .allSatisfy(observation -> {
                    assertThat(observation.qualityStatus())
                            .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                    assertThat(observation.value()).isNull();
                });
    }

    @Test
    void everyDailyDerivedIndicatorRejectsNonAvailableHistory() {
        for (RiskDataQualityStatus invalidQuality : List.of(
                RiskDataQualityStatus.STALE,
                RiskDataQualityStatus.UNAVAILABLE,
                RiskDataQualityStatus.INSUFFICIENT_HISTORY
        )) {
            List<MarketSourceRecord> records = new ArrayList<>(volatileDailyPoints(30));
            int invalidIndex = records.size() - 2;
            MarketDailyPoint original = (MarketDailyPoint) records.get(invalidIndex);
            records.set(invalidIndex, new MarketDailyPoint(
                    original.object(), original.tradeDate(), original.open(), original.close(), original.volume(),
                    original.benchmarkClose(), original.leaderClose(), original.observedAt(), original.availableAt(),
                    original.source(), invalidQuality
            ));

            RiskProviderBatch batch = provider(records, null).fetch("market_daily", request(null));

            assertThat(batch.observations()).filteredOn(observation ->
                            observation.tradeDate().equals(END_DATE)
                                    && observation.horizon() == RiskHorizon.SHORT_TERM
                                    && List.of("V3", "V4", "C1", "C3", "C4", "C5", "A3", "A5")
                                    .contains(observation.indicatorCode()))
                    .as("history quality %s", invalidQuality)
                    .hasSize(9)
                    .allSatisfy(observation -> {
                        assertThat(observation.qualityStatus())
                                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                        assertThat(observation.value()).isNull();
                    });
        }
    }

    @Test
    void c3NeedsOnlyTwoAvailablePointsForNonDownDayButRequiresVolumeContextWhenDown() {
        RiskProviderBatch nonDownBatch = provider(dailyPoints(2), null)
                .fetch("market_daily", request(null));
        List<MarketSourceRecord> downRecords = new ArrayList<>(dailyPoints(2));
        MarketDailyPoint latest = (MarketDailyPoint) downRecords.getLast();
        downRecords.set(1, new MarketDailyPoint(
                latest.object(), latest.tradeDate(), new BigDecimal("89"), new BigDecimal("90"), latest.volume(),
                latest.benchmarkClose(), latest.leaderClose(), latest.observedAt(), latest.availableAt(),
                latest.source(), RiskDataQualityStatus.AVAILABLE
        ));
        RiskProviderBatch downBatch = provider(downRecords, null)
                .fetch("market_daily", request(null));

        assertThat(shortTermLatestMetric(nonDownBatch, "C3", "downVolumeRatio"))
                .satisfies(observation -> {
                    assertThat(observation.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
                    assertThat(observation.value()).isZero();
                });
        assertThat(shortTermLatestMetric(downBatch, "C3", "downVolumeRatio"))
                .satisfies(observation -> {
                    assertThat(observation.qualityStatus())
                            .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
                    assertThat(observation.value()).isNull();
                });
    }

    @Test
    void lateHistoricalRevisionDoesNotLeakIntoEarlierDailyProxyObservations() {
        List<MarketSourceRecord> originalRecords = volatileDailyPoints(30);
        RiskProviderBatch originalBatch = provider(originalRecords, null)
                .fetch("market_daily", request(null));
        List<MarketSourceRecord> revisedRecords = new ArrayList<>(originalRecords);
        MarketDailyPoint revisedSource = (MarketDailyPoint) revisedRecords.get(revisedRecords.size() - 3);
        revisedRecords.add(new MarketDailyPoint(
                revisedSource.object(), revisedSource.tradeDate(), new BigDecimal("999"), new BigDecimal("999"),
                revisedSource.volume(), new BigDecimal("888"), revisedSource.leaderClose(),
                END_DATE.atTime(9, 0), END_DATE.atTime(10, 0), "late-revision", RiskDataQualityStatus.AVAILABLE
        ));
        RiskProviderBatch revisedBatch = provider(revisedRecords, null)
                .fetch("market_daily", request(null));
        LocalDate earlierDate = END_DATE.minusDays(1);

        assertThat(dailyDerivedValues(revisedBatch, earlierDate))
                .isEqualTo(dailyDerivedValues(originalBatch, earlierDate));
        assertThat(revisedBatch.observations()).filteredOn(observation ->
                        observation.tradeDate().equals(earlierDate)
                                && (observation.indicatorCode().equals("A3")
                                || observation.indicatorCode().equals("A5")))
                .allSatisfy(observation -> assertThat(observation.availableAt())
                        .isEqualTo(earlierDate.atTime(16, 0)));
    }

    @Test
    void lateHistoricalRevisionDoesNotLeakIntoEarlierCrossMarketS1() {
        List<MarketSourceRecord> originalRecords = crossMarketPoints(70);
        RiskProviderBatch originalBatch = provider(originalRecords, null)
                .fetch("cross_market", request(null));
        List<MarketSourceRecord> revisedRecords = new ArrayList<>(originalRecords);
        CrossMarketPoint revisedSource = (CrossMarketPoint) revisedRecords.get(revisedRecords.size() - 3);
        revisedRecords.add(new CrossMarketPoint(
                revisedSource.object(), revisedSource.tradeDate(), new BigDecimal("0.99"),
                revisedSource.dynamicCorrelation(), revisedSource.confirmedDownMarketCount(),
                revisedSource.observedMarketCount(), END_DATE.atTime(8, 0), END_DATE.atTime(8, 30),
                "late-revision", RiskDataQualityStatus.AVAILABLE
        ));
        RiskProviderBatch revisedBatch = provider(revisedRecords, null)
                .fetch("cross_market", request(null));
        LocalDate earlierDate = END_DATE.minusDays(1);

        assertThat(metricValue(revisedBatch, earlierDate, "S1", "standardizedLeadingReturn"))
                .isEqualByComparingTo(metricValue(
                        originalBatch, earlierDate, "S1", "standardizedLeadingReturn"
                ));
        assertThat(revisedBatch.observations()).filteredOn(observation ->
                        observation.tradeDate().equals(earlierDate)
                                && observation.horizon() == RiskHorizon.SHORT_TERM
                                && observation.indicatorCode().equals("S1"))
                .singleElement()
                .satisfies(observation -> assertThat(observation.availableAt())
                        .isEqualTo(earlierDate.atTime(9, 0)));
        assertThat(metricValue(revisedBatch, END_DATE, "S1", "standardizedLeadingReturn"))
                .isNotEqualByComparingTo(metricValue(
                        originalBatch, END_DATE, "S1", "standardizedLeadingReturn"
                ));
        assertThat(revisedBatch.observations()).filteredOn(observation ->
                        observation.tradeDate().equals(END_DATE)
                                && observation.horizon() == RiskHorizon.SHORT_TERM
                                && observation.indicatorCode().equals("S1"))
                .singleElement()
                .satisfies(observation -> assertThat(observation.availableAt())
                        .isEqualTo(END_DATE.atTime(9, 0)));
    }

    @Test
    void conflictingRevisionsWithIdenticalTimestampsFailDeterministically() {
        MarketDailyPoint original = (MarketDailyPoint) dailyPoints(1).getFirst();
        MarketDailyPoint conflict = new MarketDailyPoint(
                original.object(), original.tradeDate(), original.open(), new BigDecimal("999"), original.volume(),
                original.benchmarkClose(), original.leaderClose(), original.observedAt(), original.availableAt(),
                "conflict", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch firstOrder = provider(List.of(original, conflict), null)
                .fetch("market_daily", request(null));
        RiskProviderBatch reversedOrder = provider(List.of(conflict, original), null)
                .fetch("market_daily", request(null));

        assertThat(firstOrder.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(reversedOrder.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(firstOrder.errorMessage()).isEqualTo(reversedOrder.errorMessage())
                .contains("ambiguous source revisions");
    }

    @Test
    void fiveYearMultiObjectBackfillCompletesWithinBoundedWindowComplexity() {
        List<RiskObjectKey> objects = List.of(
                new RiskObjectKey(RiskObjectType.STOCK, "000001.SZ"),
                new RiskObjectKey(RiskObjectType.STOCK, "000002.SZ"),
                new RiskObjectKey(RiskObjectType.STOCK, "600519.SH"),
                new RiskObjectKey(RiskObjectType.STOCK, "600000.SH"),
                new RiskObjectKey(RiskObjectType.STOCK, "300750.SZ"),
                new RiskObjectKey(RiskObjectType.STOCK, "920992.BJ"),
                new RiskObjectKey(RiskObjectType.STOCK, "000333.SZ"),
                new RiskObjectKey(RiskObjectType.STOCK, "601318.SH")
        );
        List<MarketSourceRecord> records = objects.stream()
                .flatMap(object -> dailyPoints(object, 1250).stream())
                .toList();
        RiskProviderRequest backfillRequest = new RiskProviderRequest(
                objects, List.of(RiskHorizon.SHORT_TERM), END_DATE.minusDays(1249), END_DATE, null
        );

        assertTimeout(Duration.ofSeconds(6), () -> {
            RiskProviderBatch batch = provider(records, null).fetch("market_daily", backfillRequest);
            assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
            assertThat(batch.observations()).hasSize(1250 * objects.size() * 9);
        });
    }

    @Test
    void breadthAndCrossMarketExposeC2S1S2S4AndCoverageQuality() {
        List<MarketSourceRecord> breadth = List.of(new BreadthPoint(
                MARKET, END_DATE, 60, 40, 15, 5, 70, 100,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        ));
        MarketRiskDataProvider breadthProvider = provider(breadth, null);
        RiskProviderBatch breadthBatch = breadthProvider.fetch("breadth", request(null));

        assertThat(breadthBatch.observations()).extracting(RiskObservation::indicatorCode)
                .containsOnly("C2", "A4");
        assertThat(breadthBatch.observations())
                .filteredOn(observation -> observation.indicatorCode().equals("A4"))
                .hasSize(9)
                .allSatisfy(observation -> {
                    assertThat(observation.dimension()).isEqualTo(RiskDimension.FORCED_SELLING);
                    assertThat(observation.attributes()).containsEntry("proxy", true)
                            .containsEntry("proxyFormula", "dailyBreadthLiquidityDepth");
                    assertThat(observation.observedAt()).isEqualTo(END_DATE.atTime(15, 0));
                    assertThat(observation.availableAt()).isEqualTo(END_DATE.atTime(16, 0));
                });
        assertThat(breadthProvider.coverageReport(
                "breadth", breadthBatch, MARKET, RiskHorizon.SHORT_TERM, END_DATE, END_DATE.atTime(23, 59, 59)
        ).weightedCoverageRatio())
                .isEqualByComparingTo(BigDecimal.ONE);

        List<MarketSourceRecord> cross = crossMarketPoints(65);
        RiskProviderBatch crossBatch = provider(cross, null).fetch("cross_market", request(null));
        assertThat(crossBatch.observations()).extracting(RiskObservation::indicatorCode)
                .contains("S1", "S2", "S4");
    }

    @Test
    void stockMasterAndMembershipRemainMetadataAndAcceptUnknownListEndDates() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey stock = catalog.stock("000001.SZ");
        StockMasterPoint master = new StockMasterPoint(
                stock, END_DATE, "平安银行", null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure exposure = new IndustryExposure(
                stock, catalog.sector("801780"), LocalDate.of(2025, 1, 1), null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch masterBatch = provider(List.of(master), null)
                .fetch("cn_a_stock_master", request(null, END_DATE, stock));
        RiskProviderBatch exposureBatch = provider(List.of(exposure), null)
                .fetch("sw1_membership", request(null, END_DATE, stock));

        assertThat(masterBatch.observations()).extracting(RiskObservation::indicatorCode)
                .containsOnly("DATA_STOCK_MASTER");
        assertThat(exposureBatch.observations()).extracting(RiskObservation::indicatorCode)
                .containsOnly("DATA_SW1_MEMBERSHIP");
        assertThat(exposureBatch.observations()).allSatisfy(observation ->
                assertThat(observation.attributes()).containsEntry("sectorId", "SW1:801780"));
        assertThat(exposureBatch.industryExposures()).singleElement().satisfies(actual -> {
            assertThat(actual.stock()).isEqualTo(stock);
            assertThat(actual.sector()).isEqualTo(catalog.sector("801780"));
            assertThat(actual.validFrom()).isEqualTo(LocalDate.of(2025, 1, 1));
            assertThat(actual.validTo()).isNull();
            assertThat(actual.observedAt()).isEqualTo(END_DATE.atTime(15, 0));
            assertThat(actual.availableAt()).isEqualTo(END_DATE.atTime(16, 0));
            assertThat(actual.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        });
    }

    @Test
    void stockMasterPublishesOnlyExplicitlyRequestedStocks() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey requested = catalog.stock("000001.SZ");
        RiskObjectKey unknown = catalog.stock("600000.SH");
        StockMasterPoint requestedMaster = new StockMasterPoint(
                requested, END_DATE, "平安银行", null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );
        StockMasterPoint unknownMaster = new StockMasterPoint(
                unknown, END_DATE, "浦发银行", null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch batch = provider(List.of(requestedMaster, unknownMaster), null)
                .fetch("cn_a_stock_master", request(null, END_DATE, requested));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.observations()).hasSize(3)
                .allSatisfy(observation -> {
                    assertThat(observation.object()).isEqualTo(requested);
                    assertThat(observation.indicatorCode()).isEqualTo("DATA_STOCK_MASTER");
                });
        assertThat(batch.observations()).extracting(RiskObservation::object).doesNotContain(unknown);
        assertThat(batch.industryExposures()).isEmpty();
    }

    @Test
    void membershipPublishesTypedAndAuditRecordsOnlyForExplicitlyRequestedStocks() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey requested = catalog.stock("000001.SZ");
        RiskObjectKey unknown = catalog.stock("600000.SH");
        IndustryExposure requestedExposure = new IndustryExposure(
                requested, catalog.sector("801780"), LocalDate.of(2025, 1, 1), null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure unknownExposure = new IndustryExposure(
                unknown, catalog.sector("801780"), LocalDate.of(2025, 1, 1), null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch batch = provider(List.of(requestedExposure, unknownExposure), null)
                .fetch("sw1_membership", request(null, END_DATE, requested));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.industryExposures()).containsExactly(requestedExposure).doesNotContain(unknownExposure);
        assertThat(batch.observations()).hasSize(3)
                .allSatisfy(observation -> {
                    assertThat(observation.object()).isEqualTo(requested);
                    assertThat(observation.indicatorCode()).isEqualTo("DATA_SW1_MEMBERSHIP");
                });
        assertThat(batch.observations()).extracting(RiskObservation::object).doesNotContain(unknown);
    }

    @Test
    void membershipTypedExposuresArePointInTimeFilteredAndDeduplicatedAcrossRepeatedFetches() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey stock = catalog.stock("000001.SZ");
        IndustryExposure original = new IndustryExposure(
                stock, catalog.sector("801010"), LocalDate.of(2020, 1, 1), LocalDate.of(2024, 12, 31),
                END_DATE.minusDays(2).atTime(15, 0), END_DATE.minusDays(2).atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure current = new IndustryExposure(
                stock, catalog.sector("801780"), LocalDate.of(2025, 1, 1), null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure futureKnown = new IndustryExposure(
                stock, catalog.sector("801790"), LocalDate.of(2026, 1, 1), null,
                END_DATE.plusDays(1).atTime(8, 0), END_DATE.plusDays(1).atTime(9, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );
        MarketRiskDataProvider provider = provider(
                List.of(original, original, current, current, futureKnown), null
        );

        RiskProviderBatch first = provider.fetch("sw1_membership", request(null, END_DATE, stock));
        RiskProviderBatch second = provider.fetch("sw1_membership", request(null, END_DATE, stock));

        assertThat(first.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(first.industryExposures()).containsExactly(original, current);
        assertThat(first.industryExposures().getFirst()).satisfies(actual -> {
            assertThat(actual.validFrom()).isEqualTo(LocalDate.of(2020, 1, 1));
            assertThat(actual.validTo()).isEqualTo(LocalDate.of(2024, 12, 31));
            assertThat(actual.availableAt()).isEqualTo(END_DATE.minusDays(2).atTime(16, 0));
            assertThat(actual.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        });
        assertThat(second.industryExposures()).containsExactlyElementsOf(first.industryExposures());
        assertThat(first.observations()).extracting(observation -> observation.attributes().get("observationKey"))
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(second.observations().stream()
                        .map(observation -> observation.attributes().get("observationKey"))
                        .toList());
    }

    @Test
    void membershipRevisionIgnoresValidToAndChoosesLatestRegardlessOfSourceOrder() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey stock = catalog.stock("000001.SZ");
        LocalDate validFrom = LocalDate.of(2025, 1, 1);
        IndustryExposure openRevision = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, null,
                END_DATE.minusDays(2).atTime(15, 0), END_DATE.minusDays(2).atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure closedRevision = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, LocalDate.of(2026, 6, 30),
                END_DATE.minusDays(1).atTime(15, 0), END_DATE.minusDays(1).atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch forward = provider(List.of(openRevision, closedRevision), null)
                .fetch("sw1_membership", request(null, END_DATE, stock));
        RiskProviderBatch reversed = provider(List.of(closedRevision, openRevision), null)
                .fetch("sw1_membership", request(null, END_DATE, stock));

        assertThat(forward.industryExposures()).containsExactly(closedRevision);
        assertThat(reversed.industryExposures()).containsExactly(closedRevision);
        assertThat(forward.observations()).hasSize(3).allSatisfy(observation -> {
            assertThat(observation.indicatorCode()).isEqualTo("DATA_SW1_MEMBERSHIP");
            assertThat(observation.attributes())
                    .containsEntry("sectorId", "SW1:801780")
                    .containsEntry("validFrom", validFrom)
                    .containsEntry("validTo", LocalDate.of(2026, 6, 30));
            assertThat(observation.availableAt()).isEqualTo(closedRevision.availableAt());
        });
        assertThat(forward.observations())
                .extracting(observation -> observation.attributes().get("observationKey"))
                .doesNotHaveDuplicates();
        assertThat(reversed.observations()).containsExactlyElementsOf(forward.observations());
    }

    @Test
    void membershipRevisionUsesOnlyVersionsAvailableByRequestCutoff() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey stock = catalog.stock("000001.SZ");
        LocalDate validFrom = LocalDate.of(2025, 1, 1);
        IndustryExposure openRevision = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, null,
                END_DATE.minusDays(2).atTime(15, 0), END_DATE.minusDays(2).atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure closedRevision = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, LocalDate.of(2026, 6, 30),
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );
        MarketRiskDataProvider provider = provider(List.of(openRevision, closedRevision), null);

        RiskProviderBatch beforeRevision = provider.fetch(
                "sw1_membership", request(null, END_DATE.minusDays(1), stock)
        );
        RiskProviderBatch afterRevision = provider.fetch("sw1_membership", request(null, END_DATE, stock));

        assertThat(beforeRevision.industryExposures()).containsExactly(openRevision);
        assertThat(afterRevision.industryExposures()).containsExactly(closedRevision);
        assertThat(beforeRevision.observations()).allSatisfy(observation ->
                assertThat(observation.attributes()).doesNotContainKey("validTo"));
        assertThat(afterRevision.observations()).allSatisfy(observation ->
                assertThat(observation.attributes())
                        .containsEntry("validTo", LocalDate.of(2026, 6, 30)));
    }

    @Test
    void membershipRevisionIdentityKeepsIndependentSourcesSeparate() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey stock = catalog.stock("000001.SZ");
        LocalDate validFrom = LocalDate.of(2025, 1, 1);
        LocalDateTime observedAt = END_DATE.minusDays(1).atTime(15, 0);
        LocalDateTime availableAt = END_DATE.minusDays(1).atTime(16, 0);
        IndustryExposure primary = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, null,
                observedAt, availableAt, "primary", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure supplemental = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, null,
                observedAt, availableAt, "supplemental", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch batch = provider(List.of(primary, supplemental), null)
                .fetch("sw1_membership", request(null, END_DATE, stock));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(batch.industryExposures()).containsExactly(primary, supplemental);
        assertThat(batch.observations()).extracting(RiskObservation::source)
                .containsExactly("primary", "primary", "primary", "supplemental", "supplemental", "supplemental");
    }

    @Test
    void membershipConflictingRevisionAtSameTimestampsIsUnavailable() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        RiskObjectKey stock = catalog.stock("000001.SZ");
        LocalDate validFrom = LocalDate.of(2025, 1, 1);
        LocalDateTime observedAt = END_DATE.minusDays(1).atTime(15, 0);
        LocalDateTime availableAt = END_DATE.minusDays(1).atTime(16, 0);
        IndustryExposure openRevision = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, null,
                observedAt, availableAt, "fixed", RiskDataQualityStatus.AVAILABLE
        );
        IndustryExposure closedRevision = new IndustryExposure(
                stock, catalog.sector("801780"), validFrom, LocalDate.of(2026, 6, 30),
                observedAt, availableAt, "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch batch = provider(List.of(openRevision, closedRevision), null)
                .fetch("sw1_membership", request(null, END_DATE, stock));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(batch.errorMessage()).contains("ambiguous source revisions");
        assertThat(batch.industryExposures()).isEmpty();
        assertThat(batch.observations()).isEmpty();
    }

    @Test
    void membershipRejectsNonCanonicalSectorBeforePublishingTypedOrAuditRecords() {
        AshareRiskObjectCatalog catalog = new AshareRiskObjectCatalog();
        IndustryExposure invalid = new IndustryExposure(
                catalog.stock("000001.SZ"), new RiskObjectKey(RiskObjectType.SECTOR, "INVALID"),
                LocalDate.of(2025, 1, 1), null,
                END_DATE.minusDays(1).atTime(15, 0), END_DATE.minusDays(1).atTime(16, 0),
                "fixed", RiskDataQualityStatus.AVAILABLE
        );

        RiskProviderBatch batch = provider(List.of(invalid), null)
                .fetch("sw1_membership", request(null, END_DATE, invalid.stock()));

        assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(batch.errorMessage()).contains("non-canonical A-share risk object");
        assertThat(batch.industryExposures()).isEmpty();
        assertThat(batch.observations()).isEmpty();
    }

    @Test
    void coverageUsesExactObjectHorizonTradeDateAndAsOfTuple() {
        MarketRiskDataProvider provider = provider(dailyPoints(65), null);
        RiskProviderBatch batch = provider.fetch("market_daily", request(null));

        MarketRiskCoverageReport latestLong = provider.coverageReport(
                "market_daily", batch, MARKET, RiskHorizon.LONG_TERM, END_DATE, END_DATE.atTime(23, 59, 59)
        );
        Map<String, MarketRiskCoverageItem> byIndicator = latestLong.items().stream()
                .collect(Collectors.toMap(MarketRiskCoverageItem::indicatorCode, item -> item));

        assertThat(byIndicator.get("V4").qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(byIndicator.get("A3").qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(byIndicator.get("C5").qualityStatus())
                .isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(latestLong.weightedCoverageRatio()).isEqualByComparingTo("0.6296296296");

        MarketRiskCoverageReport beforeAvailability = provider.coverageReport(
                "market_daily", batch, MARKET, RiskHorizon.LONG_TERM, END_DATE, END_DATE.atTime(15, 30)
        );
        assertThat(beforeAvailability.items()).allSatisfy(item ->
                assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE));
    }

    @Test
    void compositeV1CoverageRequiresPeAndRiskPremiumComponents() {
        ValuationPoint partial = new ValuationPoint(
                MARKET, END_DATE, new BigDecimal("18"), new BigDecimal("0.04"), null,
                END_DATE.atTime(15, 0), END_DATE.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
        );
        MarketRiskDataProvider provider = provider(List.of(partial), null);
        RiskProviderBatch batch = provider.fetch("valuation", request(null));

        MarketRiskCoverageReport report = provider.coverageReport(
                "valuation", batch, MARKET, RiskHorizon.SHORT_TERM, END_DATE, END_DATE.atTime(23, 59, 59)
        );

        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.indicatorCode()).isEqualTo("V1");
            assertThat(item.observationCount()).isEqualTo(2);
            assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        });
        assertThat(report.weightedCoverageRatio()).isEqualByComparingTo(BigDecimal.ZERO.setScale(10));
    }

    private MarketRiskDataProvider provider(
            List<MarketSourceRecord> records,
            RiskIngestionCheckpoint checkpoint
    ) {
        return new MarketRiskDataProvider((dataset, request) ->
                new MarketSourceBatch("fixed", records, checkpoint, FETCHED_AT));
    }

    private RiskProviderRequest request(RiskIngestionCheckpoint checkpoint) {
        return request(checkpoint, END_DATE, MARKET);
    }

    private RiskProviderRequest request(RiskIngestionCheckpoint checkpoint, LocalDate endDate) {
        return request(checkpoint, endDate, MARKET);
    }

    private RiskProviderRequest request(
            RiskIngestionCheckpoint checkpoint,
            LocalDate endDate,
            RiskObjectKey... objects
    ) {
        return new RiskProviderRequest(
                List.of(objects),
                List.of(RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM),
                LocalDate.of(2021, 7, 18),
                endDate,
                checkpoint
        );
    }

    private ValuationPoint valuation(LocalDate date, LocalDateTime availableAt, String pe) {
        return new ValuationPoint(
                MARKET, date, new BigDecimal(pe), new BigDecimal("0.04"), new BigDecimal("0.005"),
                availableAt.minusHours(1), availableAt, "fixed", RiskDataQualityStatus.AVAILABLE
        );
    }

    private List<MarketSourceRecord> dailyPoints(int size) {
        return dailyPoints(MARKET, size);
    }

    private List<MarketSourceRecord> dailyPoints(RiskObjectKey object, int size) {
        List<MarketSourceRecord> records = new ArrayList<>();
        LocalDate first = END_DATE.minusDays(size - 1L);
        for (int index = 0; index < size; index++) {
            LocalDate date = first.plusDays(index);
            BigDecimal close = new BigDecimal("100").add(BigDecimal.valueOf(index));
            records.add(new MarketDailyPoint(
                    object, date, close.subtract(BigDecimal.ONE), close, BigDecimal.valueOf(1000L + index),
                    new BigDecimal("200").add(BigDecimal.valueOf(index)),
                    new BigDecimal("300").subtract(BigDecimal.valueOf(index).multiply(new BigDecimal("0.2"))),
                    date.atTime(15, 0), date.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
            ));
        }
        return records;
    }

    private BigDecimal metricValue(
            RiskProviderBatch batch,
            LocalDate tradeDate,
            String indicatorCode,
            String metric
    ) {
        return batch.observations().stream()
                .filter(observation -> observation.tradeDate().equals(tradeDate))
                .filter(observation -> observation.horizon() == RiskHorizon.SHORT_TERM)
                .filter(observation -> observation.indicatorCode().equals(indicatorCode))
                .filter(observation -> metric.equals(observation.attributes().get("metric")))
                .map(RiskObservation::value)
                .findFirst()
                .orElseThrow();
    }

    private List<MarketSourceRecord> volatileDailyPoints(int size) {
        List<MarketSourceRecord> records = new ArrayList<>();
        LocalDate first = END_DATE.minusDays(size - 1L);
        BigDecimal close = new BigDecimal("180");
        BigDecimal benchmark = new BigDecimal("240");
        for (int index = 0; index < size; index++) {
            LocalDate date = first.plusDays(index);
            BigDecimal targetReturn = index < size - 20
                    ? BigDecimal.valueOf((index % 5) - 2L).movePointLeft(3)
                    : BigDecimal.valueOf(index % 2 == 0 ? -35 : 12).movePointLeft(3);
            BigDecimal benchmarkReturn = targetReturn.multiply(new BigDecimal("0.85"));
            close = close.multiply(BigDecimal.ONE.add(targetReturn));
            benchmark = benchmark.multiply(BigDecimal.ONE.add(benchmarkReturn));
            records.add(new MarketDailyPoint(
                    MARKET, date, close, close, BigDecimal.valueOf(2000L + index),
                    benchmark, benchmark.multiply(new BigDecimal("1.01")),
                    date.atTime(15, 0), date.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
            ));
        }
        return records;
    }

    private List<MarketSourceRecord> flatRecentDailyPoints(int size) {
        List<MarketSourceRecord> records = new ArrayList<>();
        LocalDate first = END_DATE.minusDays(size - 1L);
        BigDecimal close = new BigDecimal("100");
        for (int index = 0; index < size; index++) {
            LocalDate date = first.plusDays(index);
            if (index < size - 6) {
                BigDecimal dailyReturn = BigDecimal.valueOf(index % 2 == 0 ? 2 : -2).movePointLeft(2);
                close = close.multiply(BigDecimal.ONE.add(dailyReturn));
            }
            records.add(new MarketDailyPoint(
                    MARKET, date, close, close, BigDecimal.valueOf(1000L + index),
                    close.multiply(new BigDecimal("1.1")), close.multiply(new BigDecimal("1.2")),
                    date.atTime(15, 0), date.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
            ));
        }
        return records;
    }

    private Map<String, BigDecimal> dailyDerivedValues(RiskProviderBatch batch, LocalDate tradeDate) {
        return batch.observations().stream()
                .filter(observation -> observation.tradeDate().equals(tradeDate))
                .filter(observation -> observation.horizon() == RiskHorizon.SHORT_TERM)
                .filter(observation -> List.of("V3", "V4", "C1", "C3", "C4", "C5", "A3", "A5")
                        .contains(observation.indicatorCode()))
                .collect(Collectors.toMap(
                        observation -> observation.indicatorCode() + ":" + observation.attributes().get("metric"),
                        RiskObservation::value
                ));
    }

    private RiskObservation shortTermLatestMetric(
            RiskProviderBatch batch,
            String indicatorCode,
            String metric
    ) {
        return batch.observations().stream()
                .filter(observation -> observation.tradeDate().equals(END_DATE))
                .filter(observation -> observation.horizon() == RiskHorizon.SHORT_TERM)
                .filter(observation -> observation.indicatorCode().equals(indicatorCode))
                .filter(observation -> metric.equals(observation.attributes().get("metric")))
                .findFirst()
                .orElseThrow();
    }

    private List<MarketSourceRecord> crossMarketPoints(int size) {
        List<MarketSourceRecord> records = new ArrayList<>();
        LocalDate first = END_DATE.minusDays(size - 1L);
        for (int index = 0; index < size; index++) {
            LocalDate date = first.plusDays(index);
            records.add(new CrossMarketPoint(
                    MARKET, date, new BigDecimal("-0.01").add(BigDecimal.valueOf(index).movePointLeft(4)),
                    new BigDecimal("0.70"), 2, 3,
                    date.atTime(8, 0), date.atTime(9, 0), "fixed", RiskDataQualityStatus.AVAILABLE
            ));
        }
        return records;
    }
}
