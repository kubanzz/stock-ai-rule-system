package com.jx.tracker.risk.data.market;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(provider.coverageReport("breadth", emptyBatch).items()).singleElement()
                .satisfies(item -> assertThat(item.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO));
        assertThat(provider.supportedIndicatorCodes())
                .containsExactlyInAnyOrder("V1", "V3", "V4", "S1", "S2", "S4", "C1", "C2", "C3", "C4", "C5");
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
                .contains("V3", "V4", "C1", "C3", "C4", "C5");
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
                .containsOnly("C2");
        assertThat(breadthProvider.coverageReport("breadth", breadthBatch).weightedCoverageRatio())
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
                .fetch("cn_a_stock_master", request(null));
        RiskProviderBatch exposureBatch = provider(List.of(exposure), null)
                .fetch("sw1_membership", request(null));

        assertThat(masterBatch.observations()).extracting(RiskObservation::indicatorCode)
                .containsOnly("DATA_STOCK_MASTER");
        assertThat(exposureBatch.observations()).extracting(RiskObservation::indicatorCode)
                .containsOnly("DATA_SW1_MEMBERSHIP");
        assertThat(exposureBatch.observations()).allSatisfy(observation ->
                assertThat(observation.attributes()).containsEntry("sectorId", "SW1:801780"));
    }

    private MarketRiskDataProvider provider(
            List<MarketSourceRecord> records,
            RiskIngestionCheckpoint checkpoint
    ) {
        return new MarketRiskDataProvider((dataset, request) ->
                new MarketSourceBatch("fixed", records, checkpoint, FETCHED_AT));
    }

    private RiskProviderRequest request(RiskIngestionCheckpoint checkpoint) {
        return new RiskProviderRequest(
                List.of(MARKET),
                List.of(RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM, RiskHorizon.LONG_TERM),
                LocalDate.of(2021, 7, 18),
                END_DATE,
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
        List<MarketSourceRecord> records = new ArrayList<>();
        LocalDate first = END_DATE.minusDays(size - 1L);
        for (int index = 0; index < size; index++) {
            LocalDate date = first.plusDays(index);
            BigDecimal close = new BigDecimal("100").add(BigDecimal.valueOf(index));
            records.add(new MarketDailyPoint(
                    MARKET, date, close.subtract(BigDecimal.ONE), close, BigDecimal.valueOf(1000L + index),
                    new BigDecimal("200").add(BigDecimal.valueOf(index)),
                    new BigDecimal("300").subtract(BigDecimal.valueOf(index).multiply(new BigDecimal("0.2"))),
                    date.atTime(15, 0), date.atTime(16, 0), "fixed", RiskDataQualityStatus.AVAILABLE
            ));
        }
        return records;
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
