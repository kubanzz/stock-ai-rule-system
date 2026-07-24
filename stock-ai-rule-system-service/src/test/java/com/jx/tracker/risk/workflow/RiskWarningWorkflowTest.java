package com.jx.tracker.risk.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
import com.jx.tracker.risk.engine.RiskLayerScoreRequest;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.engine.RiskScoringEngine;
import com.jx.tracker.risk.engine.RiskIndicatorCatalog;
import com.jx.tracker.risk.engine.RiskIndicatorComponentCatalog;
import com.jx.tracker.risk.data.flow.FlowEventRiskDataProvider;
import com.jx.tracker.risk.data.flow.AkToolsFlowEventSourceClient;
import com.jx.tracker.risk.data.flow.FlowEventSourceBatch;
import com.jx.tracker.risk.data.flow.FlowEventSourceRecord;
import com.jx.tracker.risk.data.market.IndustryExposure;
import com.jx.tracker.risk.data.market.MarketDatasetCode;
import com.jx.tracker.risk.data.market.MarketRiskDataProvider;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.gate.ShadowRiskGate;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import com.jx.tracker.risk.model.SignalDirection;
import com.jx.tracker.risk.provider.RiskDataProvider;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;
import com.jx.tracker.risk.provider.RiskProviderRequest;
import com.jx.tracker.risk.runtime.RiskWorkflowPlan;
import com.jx.tracker.risk.runtime.RiskWorkflowPlanner;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RiskWarningWorkflowTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 18, 20, 0);
    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey SECTOR = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780");
    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");

    @Test
    void dailyRunWritesEveryArtifactIdempotentlyResumesCheckpointAndRejectsFutureData() {
        InMemoryRepository repository = new InMemoryRepository();
        addExposure(repository);
        CapturingProvider provider = new CapturingProvider(layeredAvailableBatch());
        RiskWarningWorkflow workflow = workflow(repository, provider);
        RiskSignalCandidate signal = new RiskSignalCandidate(
                "signal:600519.SH:2026-07-18", STOCK, RiskHorizon.SHORT_TERM, DATE,
                SignalDirection.BULLISH, new BigDecimal("0.85"), List.of(MARKET, SECTOR, STOCK));
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "all", List.of(MARKET, SECTOR, STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(signal), "risk-v1");

        RiskWorkflowRunSummary first = workflow.run(request);
        RiskWorkflowRunSummary second = workflow.run(request);

        assertThat(request.collectionStartDate()).isEqualTo(DATE.minusYears(6));
        assertThat(request.scoreStartDate()).isEqualTo(DATE);
        assertThat(provider.requests()).hasSize(2);
        assertThat(provider.requests().get(0).checkpoint()).isNull();
        assertThat(provider.requests().get(1).checkpoint()).isNotNull();
        assertThat(provider.requests().get(1).checkpoint().cursor()).isEqualTo("cursor-2");
        assertThat(repository.observations).hasSize(3);
        assertThat(repository.events).hasSize(1);
        assertThat(repository.snapshots).isNotEmpty();
        assertThat(repository.evidence).hasSize(5);
        assertThat(repository.gates).hasSize(1);
        assertThat(repository.checkpoints).hasSize(1);
        assertThat(repository.checkpoints.values()).allSatisfy(checkpoint ->
                assertThat(checkpoint.scopeKey())
                        .startsWith("scope:v1:n=")
                        .contains(":sha256=")
                        .hasSizeLessThanOrEqualTo(128));
        assertThat(provider.requests().get(1).checkpoint().scopeKey())
                .isEqualTo(RiskCollectionScope.providerKey(List.of(MARKET, SECTOR, STOCK)));
        assertThat(repository.observations.values()).allMatch(observation -> !observation.availableAt().isAfter(AS_OF));
        assertThat(repository.events.values()).allMatch(event -> !event.availableAt().isAfter(AS_OF));
        assertThat(repository.gates.values()).allMatch(result -> !result.decision().enforced());
        assertThat(signal.direction()).isEqualTo(SignalDirection.BULLISH);
        assertThat(first.gateCount()).isEqualTo(1);
        assertThat(second.gateCount()).isEqualTo(1);
    }

    @Test
    void fiveYearBackfillScoresTheWholeWindowAndUnavailableSourceDoesNotAdvanceOrGate() {
        RiskWorkflowRequest backfill = RiskWorkflowRequest.fiveYearBackfill(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");
        assertThat(backfill.collectionStartDate()).isEqualTo(DATE.minusYears(11));
        assertThat(backfill.scoreStartDate()).isEqualTo(DATE.minusYears(5));

        InMemoryRepository repository = new InMemoryRepository();
        RiskDataProvider unavailable = new CapturingProvider(RiskProviderBatch.unavailable(
                "source-a", "source failed", AS_OF));
        RiskWorkflowRunSummary summary = workflow(repository, unavailable).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(summary.unavailableDatasetCount()).isEqualTo(1);
        assertThat(repository.checkpoints).isEmpty();
        assertThat(repository.ingestionStatuses).singleElement().satisfies(batch -> {
            assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
            assertThat(batch.errorMessage()).isEqualTo("source failed");
        });
        assertThat(repository.gates).isEmpty();
    }

    @Test
    void validZeroBatchPersistsSuccessfulZeroStatusWithoutInventingRecords() {
        InMemoryRepository repository = new InMemoryRepository();
        RiskProviderBatch zero = RiskProviderBatch.validZero("source-a", null, AS_OF);

        workflow(repository, new CapturingProvider(zero)).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(repository.observations).isEmpty();
        assertThat(repository.events).isEmpty();
        assertThat(repository.ingestionStatuses).singleElement()
                .extracting(RiskProviderBatch::qualityStatus)
                .isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(repository.checkpoints).isEmpty();
    }

    @Test
    void availableBatchWithoutCursorPersistsSuccessfulIngestionStatus() {
        InMemoryRepository repository = new InMemoryRepository();
        RiskProviderBatch available = new RiskProviderBatch(
                "derived-gateway", List.of(observation("S1", DATE, AS_OF.minusMinutes(1))),
                List.of(), null, RiskDataQualityStatus.AVAILABLE, null, AS_OF);

        workflow(repository, new CapturingProvider(available)).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "market:CN-A", List.of(MARKET))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(repository.checkpoints).isEmpty();
        assertThat(repository.ingestionStatuses).singleElement().satisfies(batch -> {
            assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
            assertThat(batch.errorMessage()).isNull();
        });
    }

    @Test
    void partialInsufficientHistoryBatchPersistsCurrentRecordsAndFailureStatusWithoutCheckpoint() {
        InMemoryRepository repository = new InMemoryRepository();
        IndustryExposure currentExposure = new IndustryExposure(
                STOCK, SECTOR, DATE.minusYears(2), null,
                AS_OF.minusMinutes(1), AS_OF.minusMinutes(1), "aktools",
                RiskDataQualityStatus.AVAILABLE);
        RiskIngestionCheckpoint proposedCheckpoint = new RiskIngestionCheckpoint(
                "sw1_membership", "stock:600519.SH", "cursor-current", AS_OF);
        RiskObservation auditObservation = new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, DATE, RiskDimension.STRUCTURAL_FRAGILITY,
                "V1", null, "score", AS_OF.minusMinutes(2), AS_OF.minusMinutes(1),
                "aktools", RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                Map.of("auditValue", new BigDecimal("90"), "sourceQuality", "available"));
        RiskEvent availableEvent = event("recent-reduction", AS_OF.minusMinutes(1));
        RiskEvent currentEvent = new RiskEvent(
                availableEvent.object(), availableEvent.tradeDate(), availableEvent.dimension(),
                availableEvent.eventType(), availableEvent.eventKey(), availableEvent.severityScore(),
                availableEvent.occurredAt(), availableEvent.observedAt(), availableEvent.availableAt(),
                availableEvent.source(), RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                Map.of("sourceQuality", "available", "auditOnly", true));
        RiskProviderBatch partial = new RiskProviderBatch(
                "aktools", List.of(auditObservation), List.of(currentEvent),
                List.of(currentExposure), proposedCheckpoint,
                RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                "current snapshot available; historical SW1 membership is insufficient", AS_OF);

        RequestCapturingEvaluator evaluator = new RequestCapturingEvaluator();
        workflow(repository, new CapturingProvider(partial), evaluator).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(repository.exposures).containsExactly(currentExposure);
        assertThat(repository.observations).containsValue(auditObservation);
        assertThat(repository.events).containsValue(currentEvent);
        assertThat(evaluator.request.evidence()).isEmpty();
        assertThat(evaluator.request.timeCorrectionFactor()).isEqualByComparingTo("1.00");
        assertThat(repository.ingestionStatuses).singleElement().satisfies(batch -> {
            assertThat(batch.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
            assertThat(batch.errorMessage()).contains("historical SW1 membership");
        });
        assertThat(repository.checkpoints).isEmpty();
    }

    @Test
    void futureAvailableProviderRowsDoNotAdvanceCheckpoint() {
        InMemoryRepository repository = new InMemoryRepository();
        RiskObservation future = observation("V2", DATE, AS_OF.plusHours(1));
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "dataset-a", "stock:600519.SH", "cursor-future", AS_OF);
        RiskProviderBatch batch = new RiskProviderBatch(
                "source-a", List.of(future), List.of(), checkpoint,
                RiskDataQualityStatus.AVAILABLE, null, AS_OF);

        workflow(repository, new CapturingProvider(batch)).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(repository.observations).isEmpty();
        assertThat(repository.checkpoints).isEmpty();
    }

    @Test
    void historicalScoreUsesSameDayAfterCloseCutoffAndExcludesNextDayAvailableData() {
        LocalDate nextDate = DATE.plusDays(1);
        LocalDateTime nextAsOf = nextDate.atTime(20, 0);
        RiskObservation sameDay = observation(MARKET, "V1", DATE, DATE.atTime(19, 30));
        RiskObservation nextDayAvailable = observation(MARKET, "V2", DATE, nextDate.atTime(9, 0));
        RiskProviderBatch batch = new RiskProviderBatch(
                "source-a", List.of(sameDay, nextDayAvailable), List.of(), null,
                RiskDataQualityStatus.AVAILABLE, null, nextAsOf);

        InMemoryRepository repository = new InMemoryRepository();
        RiskWorkflowRequest request = RiskWorkflowRequest.fiveYearBackfill(
                nextDate, nextAsOf,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "market:CN-A", List.of(MARKET))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        workflow(repository, new CapturingProvider(batch)).run(request);

        RiskSnapshot historical = repository.snapshots.values().stream()
                .map(StoredRiskSnapshot::snapshot)
                .filter(snapshot -> snapshot.tradeDate().equals(DATE))
                .findFirst().orElseThrow();
        assertThat(request.afterCloseCutoff()).isEqualTo(LocalTime.of(20, 0));
        assertThat(historical.calculatedAt()).isEqualTo(DATE.atTime(20, 0));
        assertThat(historical.evidence()).extracting(RiskEvidence::indicatorCode)
                .containsExactly("V1");
    }

    @Test
    void stockScoreUsesFixedMarketSectorStockWeightsAndMaximumConfirmedModifier() {
        RiskObjectKey market = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
        RiskObjectKey sector = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801780");
        InMemoryRepository repository = new InMemoryRepository();
        repository.exposures.add(new IndustryExposure(
                STOCK, sector, DATE.minusYears(1), null, DATE.atStartOfDay(), DATE.atStartOfDay(),
                "source-a", RiskDataQualityStatus.AVAILABLE));
        List<RiskObservation> observations = List.of(
                observation(market, "V1", new BigDecimal("20")),
                confirmationObservation(
                        RiskHorizon.SHORT_TERM, DATE,
                        RiskDimension.LOCAL_CONFIRMATION, "C1", new BigDecimal("99")),
                confirmationObservation(
                        RiskHorizon.SHORT_TERM, DATE,
                        RiskDimension.FORCED_SELLING, "A2", new BigDecimal("99")),
                observation(sector, "V1", new BigDecimal("40")),
                observation(STOCK, "V1", new BigDecimal("80")));
        LayerCapturingEvaluator evaluator = new LayerCapturingEvaluator();
        RiskEvent marketExtreme = event(market, DATE, "market-extreme", DATE.atTime(19, 0), Map.of(
                "extremePercentile", "99", "priceConfirmed", true, "fundFlowConfirmed", true));

        workflow(repository, new CapturingProvider(new RiskProviderBatch(
                "source-a", observations, List.of(marketExtreme), null,
                RiskDataQualityStatus.AVAILABLE, null, AS_OF)), evaluator)
                .run(RiskWorkflowRequest.daily(
                        DATE, AS_OF,
                        List.of(new RiskCollectionTask(
                                "provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                        List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(evaluator.layerRequest).isNotNull();
        assertThat(evaluator.layerRequest.composition().vScore()).isEqualByComparingTo("51.0000");
        assertThat(evaluator.layerRequest.composition().coverage()).isEqualByComparingTo("1.0000");
        assertThat(evaluator.layerRequest.composition().mScore()).isEqualByComparingTo("1.10");
        assertThat(evaluator.layerRequest.extremeConfirmation().permitsImmediateEscalation()).isTrue();
    }

    @Test
    void firstRunPersistsTypedMembershipAndUsesItsSectorForLaterCollectionAndStockLayer() {
        ExposureJdbcRepository exposureJdbcRepository = new ExposureJdbcRepository();
        InMemoryRepository repository = exposureJdbcRepository;
        IndustryExposure current = exposure(
                SECTOR, DATE.minusYears(1), null, AS_OF.minusHours(2), AS_OF.minusHours(1),
                RiskDataQualityStatus.AVAILABLE);
        IndustryExposure future = exposure(
                new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801790"),
                DATE.minusYears(1), null, AS_OF.minusHours(1), AS_OF.plusMinutes(1),
                RiskDataQualityStatus.AVAILABLE);
        TwoStageMarketProvider provider = new TwoStageMarketProvider(List.of(current, future), false);
        LayerCapturingEvaluator evaluator = new LayerCapturingEvaluator();
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(
                        task(MarketDatasetCode.SW1_MEMBERSHIP, List.of(STOCK)),
                        task(MarketDatasetCode.MARKET_DAILY, List.of(MARKET)),
                        task(MarketDatasetCode.MARKET_DAILY, List.of(STOCK)),
                        task(MarketDatasetCode.VALUATION, List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        assertThat(repository.exposures).isEmpty();
        assertThat(exposureJdbcRepository.persistedExposureCount()).isZero();
        workflow(repository, provider, evaluator).run(request);

        assertThat(repository.exposures).containsExactly(current);
        assertThat(exposureJdbcRepository.persistedExposureCount()).isEqualTo(1);
        assertThat(provider.stockRequest(MarketDatasetCode.MARKET_DAILY)).satisfies(actual ->
                assertThat(actual.objects()).containsExactly(STOCK, SECTOR));
        assertThat(provider.stockRequest(MarketDatasetCode.VALUATION)).satisfies(actual ->
                assertThat(actual.objects()).containsExactly(STOCK, SECTOR));
        assertThat(repository.observations.values()).extracting(RiskObservation::object)
                .contains(MARKET, SECTOR, STOCK);
        assertThat(repository.snapshots.values()).extracting(StoredRiskSnapshot::snapshot)
                .extracting(RiskSnapshot::object).contains(MARKET, SECTOR, STOCK);
        assertThat(evaluator.layerRequest).isNotNull();
        assertThat(evaluator.layerRequest.composition().vScore()).isEqualByComparingTo("51.00");
    }

    @Test
    void olderMembershipRevisionArrivingLastCannotRollbackCurrentRunExposure() {
        IndustryExposure older = exposure(
                SECTOR, DATE.minusYears(1), DATE.minusDays(1),
                AS_OF.minusHours(4), AS_OF.minusHours(3),
                RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        IndustryExposure newer = exposure(
                SECTOR, DATE.minusYears(1), null,
                AS_OF.minusHours(2), AS_OF.minusHours(1),
                RiskDataQualityStatus.AVAILABLE);
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(
                        task(MarketDatasetCode.SW1_MEMBERSHIP, List.of(STOCK)),
                        task(MarketDatasetCode.MARKET_DAILY, List.of(MARKET, STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");
        LayerCapturingEvaluator forwardEvaluator = new LayerCapturingEvaluator();
        LayerCapturingEvaluator reverseEvaluator = new LayerCapturingEvaluator();

        workflow(new InMemoryRepository(),
                new TwoStageMarketProvider(List.of(older, newer), false), forwardEvaluator).run(request);
        workflow(new InMemoryRepository(),
                new TwoStageMarketProvider(List.of(newer, older), false), reverseEvaluator).run(request);

        assertThat(forwardEvaluator.layerRequest).isNotNull();
        assertThat(reverseEvaluator.layerRequest).isNotNull();
        assertThat(reverseEvaluator.layerRequest.composition())
                .isEqualTo(forwardEvaluator.layerRequest.composition());
        assertThat(reverseEvaluator.layerRequest.composition().vScore()).isEqualByComparingTo("51.00");
    }

    @Test
    void expandedProviderRequestsRespectObjectLimitWithoutDroppingStocksOrSectors() {
        int requestLimit = 500;
        List<RiskObjectKey> stocks = java.util.stream.IntStream.range(0, requestLimit)
                .mapToObj(index -> new RiskObjectKey(
                        RiskObjectType.STOCK, String.format("%06d.SH", index + 1)))
                .toList();
        List<IndustryExposure> memberships = java.util.stream.IntStream.range(0, requestLimit)
                .mapToObj(index -> new IndustryExposure(
                        stocks.get(index),
                        new RiskObjectKey(RiskObjectType.SECTOR,
                                String.format("SW1:%06d", 800001 + index % 31)),
                        DATE.minusYears(1), null, AS_OF.minusHours(2), AS_OF.minusHours(1),
                        "aktools", RiskDataQualityStatus.AVAILABLE))
                .toList();
        Set<RiskObjectKey> expectedObjects = new LinkedHashSet<>(stocks);
        memberships.stream().map(IndustryExposure::sector).forEach(expectedObjects::add);
        TwoStageMarketProvider provider = new TwoStageMarketProvider(memberships, false);
        InMemoryRepository repository = new InMemoryRepository();

        workflow(repository, provider, defaultEvaluator(), requestLimit).run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(
                        task(MarketDatasetCode.SW1_MEMBERSHIP, stocks),
                        task(MarketDatasetCode.MARKET_DAILY, stocks)),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        List<RiskProviderRequest> requests = provider.requests(MarketDatasetCode.MARKET_DAILY);
        assertThat(requests).hasSizeGreaterThan(1)
                .allSatisfy(actual -> assertThat(actual.objects()).hasSizeLessThanOrEqualTo(requestLimit));
        assertThat(requests.stream().flatMap(actual -> actual.objects().stream()).toList())
                .containsExactlyInAnyOrderElementsOf(expectedObjects);
    }

    @Test
    void fiveYearBackfillCollectsEveryHistoricalSectorOverlappingTheWindow() {
        RiskObjectKey formerSector = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801010");
        IndustryExposure former = exposure(
                formerSector, DATE.minusYears(5), DATE.minusYears(1),
                AS_OF.minusHours(3), AS_OF.minusHours(2), RiskDataQualityStatus.AVAILABLE);
        IndustryExposure current = exposure(
                SECTOR, DATE.minusYears(1).plusDays(1), null,
                AS_OF.minusHours(2), AS_OF.minusHours(1), RiskDataQualityStatus.AVAILABLE);
        IndustryExposure future = exposure(
                new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801790"),
                DATE.minusYears(2), null, AS_OF.minusHours(1), AS_OF.plusMinutes(1),
                RiskDataQualityStatus.AVAILABLE);
        TwoStageMarketProvider provider = new TwoStageMarketProvider(
                List.of(former, current, future), false);

        workflow(new InMemoryRepository(), provider, defaultEvaluator(), 500)
                .run(RiskWorkflowRequest.fiveYearBackfill(
                        DATE, AS_OF,
                        List.of(
                                task(MarketDatasetCode.SW1_MEMBERSHIP, List.of(STOCK)),
                                task(MarketDatasetCode.MARKET_DAILY, List.of(STOCK))),
                        List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(provider.requests(MarketDatasetCode.MARKET_DAILY)
                .stream().flatMap(actual -> actual.objects().stream()).toList())
                .contains(STOCK, formerSector, SECTOR)
                .doesNotContain(future.sector());
    }

    @Test
    void unavailableMembershipFallsBackToExistingPointInTimeExposureWithoutUsingFutureRevision() {
        InMemoryRepository repository = new InMemoryRepository();
        IndustryExposure current = exposure(
                SECTOR, DATE.minusYears(1), null, AS_OF.minusHours(2), AS_OF.minusHours(1),
                RiskDataQualityStatus.AVAILABLE);
        IndustryExposure future = exposure(
                new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801790"),
                DATE.minusYears(1), null, AS_OF.minusHours(1), AS_OF.plusMinutes(1),
                RiskDataQualityStatus.AVAILABLE);
        repository.exposures.add(current);
        repository.exposures.add(future);
        TwoStageMarketProvider provider = new TwoStageMarketProvider(List.of(), true);
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(
                        task(MarketDatasetCode.SW1_MEMBERSHIP, List.of(STOCK)),
                        task(MarketDatasetCode.MARKET_DAILY, List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        workflow(repository, provider).run(request);

        assertThat(provider.stockRequest(MarketDatasetCode.MARKET_DAILY).objects())
                .containsExactly(STOCK, SECTOR)
                .doesNotContain(future.sector());
    }

    @Test
    void pointInTimeEventsDriveWindowModifierAndSameDayExtremeConfirmationOnly() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, DATE, RiskDimension.LOCAL_CONFIRMATION, "C1", new BigDecimal("99")));
        repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, DATE, RiskDimension.FORCED_SELLING, "A2", new BigDecimal("99")));
        repository.events.put("current", event(STOCK, DATE, "current", DATE.atTime(19, 0), Map.of(
                "modifierCandidate", true, "confirmed", true, "mScore", "1.15",
                "extremePercentile", "99", "priceConfirmed", true, "fundFlowConfirmed", true)));
        repository.events.put("future", event(STOCK, DATE, "future", DATE.plusDays(1).atTime(9, 0), Map.of(
                "modifierCandidate", true, "confirmed", true, "mScore", "1.20",
                "extremePercentile", "100", "priceConfirmed", true, "fundFlowConfirmed", true)));
        RequestCapturingEvaluator evaluator = new RequestCapturingEvaluator();

        workflow(repository, new CapturingProvider(RiskProviderBatch.validZero("source-a", null, AS_OF)), evaluator)
                .run(RiskWorkflowRequest.daily(
                        DATE, AS_OF,
                        List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                        List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(evaluator.request.timeCorrectionFactor()).isEqualByComparingTo("1.15");
        assertThat(evaluator.request.extremeConfirmation().percentile()).isEqualByComparingTo("99");
        assertThat(evaluator.request.extremeConfirmation().priceConfirmed()).isTrue();
        assertThat(evaluator.request.extremeConfirmation().fundFlowConfirmed()).isTrue();
    }

    @Test
    void structuralScoreAndTriggerFlagsCannotCreateExtremeEscalationWithoutPriceFundTradingEvidence() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.saveObservation(new RiskObservation(
                MARKET, RiskHorizon.SHORT_TERM, DATE, RiskDimension.STRUCTURAL_FRAGILITY,
                "V1", new BigDecimal("100"), "score", DATE.atTime(18, 0), DATE.atTime(19, 0),
                "valuation-source", RiskDataQualityStatus.AVAILABLE, Map.of()));
        repository.events.put("false-extreme", event(
                STOCK, DATE, "false-extreme", DATE.atTime(19, 0), Map.of(
                        "extremePercentile", "100",
                        "priceConfirmed", true,
                        "fundFlowConfirmed", true)));
        RequestCapturingEvaluator evaluator = new RequestCapturingEvaluator();

        workflow(repository, new CapturingProvider(RiskProviderBatch.validZero("source-a", null, AS_OF)), evaluator)
                .run(RiskWorkflowRequest.daily(
                        DATE, AS_OF,
                        List.of(new RiskCollectionTask(
                                "provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                        List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(evaluator.request.extremeConfirmation()).isEqualTo(
                com.jx.tracker.risk.engine.ExtremeRiskConfirmation.none());
        assertThat(evaluator.request.timeCorrectionFactor()).isEqualByComparingTo("1.00");
    }

    @Test
    void priceAndFundConfirmationsMustBothBelongToTheEvaluationTradingDate() {
        InMemoryRepository repository = new InMemoryRepository();
        repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, DATE,
                RiskDimension.LOCAL_CONFIRMATION, "C1", new BigDecimal("100")));
        repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, DATE,
                RiskDimension.FORCED_SELLING, "A2", new BigDecimal("100")));
        RiskEvidenceAssembler datedAssembler = (object, horizon, tradeDate, asOf, observations) -> {
            if (!object.equals(MARKET)) {
                return List.of();
            }
            return List.of(
                    new RiskEvidence(
                            RiskDimension.LOCAL_CONFIRMATION, "C1", new BigDecimal("100"),
                            new BigDecimal("100"), DATE.atTime(18, 0), DATE.atTime(19, 0),
                            "price-source", RiskDataQualityStatus.AVAILABLE,
                            Map.of("tradeDate", DATE.minusDays(1).toString(), "extremeCandidate", true)),
                    new RiskEvidence(
                            RiskDimension.FORCED_SELLING, "A2", new BigDecimal("100"),
                            new BigDecimal("100"), DATE.atTime(18, 0), DATE.atTime(19, 0),
                            "fund-source", RiskDataQualityStatus.AVAILABLE,
                            Map.of("tradeDate", DATE.toString(), "extremeCandidate", true)));
        };
        RequestCapturingEvaluator evaluator = new RequestCapturingEvaluator();
        RiskWarningWorkflow workflow = new RiskWarningWorkflow(
                List.of(new CapturingProvider(RiskProviderBatch.validZero("source-a", null, AS_OF))),
                repository, datedAssembler, evaluator, new ShadowRiskGate());

        workflow.run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(evaluator.request.extremeConfirmation().percentile()).isEqualByComparingTo("100");
        assertThat(evaluator.request.extremeConfirmation().priceConfirmed()).isFalse();
        assertThat(evaluator.request.extremeConfirmation().fundFlowConfirmed()).isTrue();
        assertThat(evaluator.request.extremeConfirmation().permitsImmediateEscalation()).isFalse();
    }

    @Test
    void weekendEventRemainsInsideFiveActualTradingDaysAfterLongHoliday() {
        LocalDate evaluationDate = LocalDate.of(2026, 10, 12);
        LocalDateTime evaluationAsOf = evaluationDate.atTime(20, 0);
        InMemoryRepository repository = new InMemoryRepository();
        List<LocalDate> tradingDates = List.of(
                LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 9), evaluationDate);
        tradingDates.forEach(date -> repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION, "C1", new BigDecimal("80"))));
        repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, evaluationDate,
                RiskDimension.FORCED_SELLING, "A2", new BigDecimal("80")));
        LocalDate weekend = LocalDate.of(2026, 10, 3);
        repository.events.put("weekend", event(
                STOCK, weekend, "weekend", weekend.atTime(19, 0), Map.of(
                        "modifierCandidate", true, "confirmed", true, "mScore", "1.15")));
        RequestCapturingEvaluator evaluator = new RequestCapturingEvaluator();

        workflow(repository,
                new CapturingProvider(RiskProviderBatch.validZero("source-a", null, evaluationAsOf)), evaluator)
                .run(RiskWorkflowRequest.daily(
                        evaluationDate, evaluationAsOf,
                        List.of(new RiskCollectionTask(
                                "provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                        List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(evaluator.request.timeCorrectionFactor()).isEqualByComparingTo("1.15");
    }

    @Test
    void fixedAkToolsHttpJsonFlowsThroughRecordProviderAndWorkflow() {
        LocalDate evaluationDate = LocalDate.of(2026, 7, 20);
        LocalDateTime evaluationAsOf = evaluationDate.atTime(20, 0);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://127.0.0.1:8090/api/public/stock_ggcg_em"))
                .andRespond(withSuccess("""
                        {
                          "meta": {"historyComplete": true, "earliestAvailableDate": "2021-07-20"},
                          "data": [{
                            "代码": "600519", "持股变动信息-增减": "减持",
                            "持股变动信息-变动数量": 1000000,
                            "持股变动信息-占流通股比例": 100,
                            "变动开始日": "2026-07-18", "变动截止日": "2026-07-18",
                            "公告日": "2026-07-18"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));
        AkToolsFlowEventSourceClient sourceClient = new AkToolsFlowEventSourceClient(
                "http://127.0.0.1:8090", builder, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneId.of("Asia/Shanghai")));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(sourceClient);
        InMemoryRepository repository = new InMemoryRepository();
        List<LocalDate> tradingDates = List.of(
                LocalDate.of(2026, 7, 14), LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 17), evaluationDate);
        tradingDates.forEach(date -> repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, date, RiskDimension.LOCAL_CONFIRMATION, "C1", new BigDecimal("99"))));
        repository.saveObservation(confirmationObservation(
                RiskHorizon.SHORT_TERM, evaluationDate,
                RiskDimension.FORCED_SELLING, "A2", new BigDecimal("99")));
        RequestCapturingEvaluator evaluator = new RequestCapturingEvaluator();

        workflow(repository, provider, evaluator).run(RiskWorkflowRequest.daily(
                evaluationDate, evaluationAsOf,
                List.of(new RiskCollectionTask(
                        "flow-event", "share_reduction", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        server.verify();
        assertThat(repository.events.values()).singleElement().satisfies(event -> {
            assertThat(event.source()).isEqualTo(AkToolsFlowEventSourceClient.SOURCE);
            assertThat(event.payload())
                    .containsEntry(
                            "sourceRecordId",
                            "share_reduction:600519.SH:未披露股东:2026-07-18:"
                                    + "2026-07-18:2026-07-18:减持:1E+6")
                    .containsEntry("actualReduction", true)
                    .containsEntry("modifierCandidate", true);
        });
        assertThat(evaluator.request.timeCorrectionFactor()).isEqualByComparingTo("1.20");
        assertThat(evaluator.request.extremeConfirmation().permitsImmediateEscalation()).isTrue();
    }

    @Test
    void productionReductionRecordUsesPointInTimeCrossLayerEvidenceForEveryHorizon() {
        InMemoryRepository repository = new InMemoryRepository();
        addExposure(repository);
        for (RiskHorizon horizon : RiskHorizon.values()) {
            for (int index = 0; index < 1_250; index++) {
                LocalDate historyDate = DATE.minusDays(1_249L - index);
                repository.saveObservation(confirmationObservation(
                        horizon, historyDate, RiskDimension.LOCAL_CONFIRMATION, "C1",
                        BigDecimal.valueOf(1_250L - index)));
                repository.saveObservation(confirmationObservation(
                        horizon, historyDate, RiskDimension.FORCED_SELLING, "A2",
                        BigDecimal.valueOf(index + 1L)));
            }
        }
        FlowEventSourceRecord reduction = new FlowEventSourceRecord(
                "reduction-prod-1", "cursor-prod-1", STOCK, DATE,
                DATE.atTime(17, 0), DATE.atTime(18, 0), DATE.atTime(19, 0),
                new BigDecimal("100"), "score", "share_reduction", "实际减持",
                Map.of(
                        "actualReduction", true,
                        "adverse", true,
                        "modifierRatio", new BigDecimal("100")));
        FlowEventRiskDataProvider provider = new FlowEventRiskDataProvider(request -> new FlowEventSourceBatch(
                "aktools", List.of(reduction), RiskDataQualityStatus.AVAILABLE, null,
                "cursor-prod-1", DATE.minusYears(5), true, DATE.atTime(19, 5), null));
        ProductionPathCapturingEvaluator evaluator = new ProductionPathCapturingEvaluator();
        RiskWarningWorkflow workflow = new RiskWarningWorkflow(
                List.of(provider), repository,
                new PercentileRiskEvidenceAssembler(new RiskNormalizer()),
                evaluator, new ShadowRiskGate());

        workflow.run(RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask(
                        "flow-event", "share_reduction", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.values()), List.of(), "risk-v1"));

        assertThat(evaluator.layerRequests).hasSize(3);
        assertThat(evaluator.layerRequests).allSatisfy(layerRequest -> {
            assertThat(layerRequest.composition().mScore()).isEqualByComparingTo("1.20");
            assertThat(layerRequest.extremeConfirmation().permitsImmediateEscalation()).isTrue();
            assertThat(layerRequest.composition().evidence())
                    .extracting(RiskEvidence::dimension)
                    .contains(RiskDimension.LOCAL_CONFIRMATION, RiskDimension.FORCED_SELLING);
        });
        assertThat(repository.events.values()).singleElement().satisfies(event -> {
            assertThat(event.availableAt()).isBeforeOrEqualTo(AS_OF);
            assertThat(event.payload())
                    .containsEntry("confirmed", false)
                    .containsEntry("confirmationContract", "pit-price-fund-evidence-v1")
                    .doesNotContainKeys("probability", "crashProbability");
            assertThat((BigDecimal) event.payload().get("modifierSeverity"))
                    .isEqualByComparingTo("100");
        });
    }

    @Test
    void realPlannerEngineAndComposerCloseWithInheritedTransmissionAndFiveYearBaseline() {
        RiskWorkflowPlan plan = new RiskWorkflowPlanner(() -> List.of(STOCK.objectId())).plan(List.of());
        InMemoryRepository repository = new InMemoryRepository();
        PlannerMarketFixtureProvider marketProvider = new PlannerMarketFixtureProvider(fullBaselineObservations());
        RiskDataProvider emptyFlowProvider = new RiskDataProvider() {
            @Override
            public String providerCode() {
                return "flow-event";
            }

            @Override
            public boolean supports(String datasetCode) {
                return true;
            }

            @Override
            public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
                return RiskProviderBatch.validZero("fixture-flow", null, AS_OF);
            }
        };
        DelegatingCapturingEvaluator evaluator = new DelegatingCapturingEvaluator();
        RiskWarningWorkflow workflow = new RiskWarningWorkflow(
                List.of(marketProvider, emptyFlowProvider), repository,
                new PercentileRiskEvidenceAssembler(new RiskNormalizer()),
                evaluator, new ShadowRiskGate());

        workflow.run(RiskWorkflowRequest.daily(
                DATE, AS_OF, plan.collectionTasks(), List.of(RiskHorizon.SHORT_TERM),
                List.of(), "risk-engine-closure-v1"));

        assertThat(plan.collectionTasks()).hasSize(13);
        RiskSnapshot market = storedSnapshot(repository, MARKET);
        RiskSnapshot sector = storedSnapshot(repository, SECTOR);
        RiskSnapshot stock = storedSnapshot(repository, STOCK);
        assertThat(List.of(market, sector, stock)).allSatisfy(snapshot -> {
            assertThat(snapshot.totalScore()).isNotNull();
            assertThat(snapshot.completeness()).isGreaterThanOrEqualTo(new BigDecimal("0.80"));
            assertThat(snapshot.sScore()).isNotNull();
        });
        assertThat(sector.evidence()).filteredOn(item -> item.dimension() == RiskDimension.EXTERNAL_TRANSMISSION)
                .allSatisfy(item -> assertThat(item.details())
                        .containsEntry("inherited", true)
                        .containsEntry("inheritedFromObjectId", "CN-A")
                        .containsEntry("layerObjectType", "market")
                        .containsEntry("layerObjectId", "CN-A"));
        assertThat(stock.sScore()).isEqualByComparingTo(market.sScore().multiply(new BigDecimal("0.25")));
        assertThat(stock.evidence()).filteredOn(item ->
                        item.dimension() == RiskDimension.EXTERNAL_TRANSMISSION)
                .allSatisfy(item -> assertThat(item.details())
                        .containsEntry("layerObjectId", "CN-A")
                        .doesNotContainKey("inherited"));
        assertThat(evaluator.rawRequests.get(STOCK).evidence()).filteredOn(item ->
                        item.dimension() == RiskDimension.EXTERNAL_TRANSMISSION)
                .allSatisfy(item -> assertThat(item.details())
                        .containsEntry("inherited", true)
                        .containsEntry("inheritedFromObjectId", "CN-A")
                        .containsEntry("layerObjectType", "market")
                        .containsEntry("layerObjectId", "CN-A"));
    }

    private RiskSnapshot storedSnapshot(InMemoryRepository repository, RiskObjectKey object) {
        return repository.snapshots.values().stream()
                .map(StoredRiskSnapshot::snapshot)
                .filter(snapshot -> snapshot.object().equals(object))
                .filter(snapshot -> snapshot.tradeDate().equals(DATE))
                .findFirst().orElseThrow();
    }

    private List<RiskObservation> fullBaselineObservations() {
        List<RiskObservation> observations = new ArrayList<>();
        List<RiskObjectKey> localObjects = List.of(MARKET, SECTOR, STOCK);
        List<LocalDate> tradingDates = new ArrayList<>();
        tradingDates.add(DATE);
        LocalDate candidate = DATE.minusDays(1);
        while (tradingDates.size() < 1_250) {
            if (candidate.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && candidate.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                tradingDates.add(candidate);
            }
            candidate = candidate.minusDays(1);
        }
        java.util.Collections.reverse(tradingDates);
        for (int index = 0; index < 1_250; index++) {
            LocalDate date = tradingDates.get(index);
            BigDecimal value = BigDecimal.valueOf(index % 100 + 1L);
            for (var definition : RiskIndicatorCatalog.definitions()) {
                if (definition.dimension() == RiskDimension.SUBSTANTIVE_TRIGGER) {
                    continue;
                }
                List<RiskObjectKey> objects = definition.dimension() == RiskDimension.EXTERNAL_TRANSMISSION
                        ? List.of(MARKET) : localObjects;
                List<String> components = RiskIndicatorComponentCatalog.components(definition.code()).stream()
                        .map(RiskIndicatorComponentCatalog.ComponentDefinition::code).toList();
                if (components.isEmpty()) {
                    components = List.of(definition.code());
                }
                for (RiskObjectKey object : objects) {
                    for (String component : components) {
                        observations.add(new RiskObservation(
                                object, RiskHorizon.SHORT_TERM, date, definition.dimension(),
                                definition.code(), component, value, "score",
                                date.atTime(18, 0), date.atTime(19, 0), "fixture-market",
                                RiskDataQualityStatus.AVAILABLE,
                                Map.of("metric", component, "datasetCode", "market_daily",
                                        "tradingDay", true, "marketPrice", true)));
                    }
                }
            }
        }
        return List.copyOf(observations);
    }

    @Test
    void recomputationReplacesEvidenceAndRemovesGateWhenSnapshotBecomesIncomplete() {
        InMemoryRepository repository = new InMemoryRepository();
        addExposure(repository);
        RiskSignalCandidate signal = new RiskSignalCandidate(
                "signal:600519.SH:2026-07-18", STOCK, RiskHorizon.SHORT_TERM, DATE,
                SignalDirection.BULLISH, new BigDecimal("0.85"), List.of(MARKET, SECTOR, STOCK));
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "all", List.of(MARKET, SECTOR, STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(signal), "risk-v1");
        RiskWarningWorkflow workflow = workflow(repository, new CapturingProvider(layeredAvailableBatch()));

        workflow.run(request);
        long originalId = repository.snapshots.values().stream()
                .filter(stored -> stored.snapshot().object().equals(STOCK))
                .findFirst().orElseThrow().id();
        assertThat(repository.gates).hasSize(1);
        repository.observations.clear();
        workflow(repository, new CapturingProvider(RiskProviderBatch.validZero("source-a", null, AS_OF))).run(request);

        assertThat(repository.snapshots.values()).filteredOn(stored -> stored.snapshot().object().equals(STOCK))
                .singleElement().satisfies(stored -> {
            assertThat(stored.id()).isEqualTo(originalId);
            assertThat(stored.snapshot().level()).isNull();
            assertThat(stored.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        });
        assertThat(repository.evidence).isEmpty();
        assertThat(repository.gates).isEmpty();
    }

    @Test
    void backfillUsesOneBatchReadPerArtifactTypeInsteadOfPerDateHistoryQueries() {
        InMemoryRepository repository = new InMemoryRepository();
        workflow(repository, new CapturingProvider(availableBatch())).run(RiskWorkflowRequest.fiveYearBackfill(
                DATE, AS_OF,
                List.of(new RiskCollectionTask("provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1"));

        assertThat(repository.observationReads).isEqualTo(1);
        assertThat(repository.eventReads).isEqualTo(1);
        assertThat(repository.exposureReads).isEqualTo(1);
        assertThat(repository.historyReads).isEqualTo(1);
    }

    @Test
    void layerObjectExpansionVisitsEachExposureOnceWithLargeStockScope() {
        int size = 5_000;
        Set<RiskObjectKey> objects = java.util.stream.IntStream.range(0, size)
                .mapToObj(index -> new RiskObjectKey(
                        RiskObjectType.STOCK, String.format("%06d.SH", index)))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<IndustryExposure> exposures = objects.stream().map(stock -> new IndustryExposure(
                stock, SECTOR, DATE.minusYears(1), null,
                DATE.atTime(18, 0), DATE.atTime(19, 0),
                "source-a", RiskDataQualityStatus.AVAILABLE)).toList();
        RiskWarningWorkflow workflow = workflow(
                new InMemoryRepository(),
                new CapturingProvider(RiskProviderBatch.validZero("source-a", null, AS_OF)));
        RiskWorkflowRequest request = RiskWorkflowRequest.daily(
                DATE, AS_OF,
                List.of(new RiskCollectionTask(
                        "provider-a", "dataset-a", "stock:600519.SH", List.of(STOCK))),
                List.of(RiskHorizon.SHORT_TERM), List.of(), "risk-v1");

        Set<RiskObjectKey> requestedStocks = Set.copyOf(objects);
        RiskWarningWorkflow.LayerObjectExpansionMetrics metrics =
                workflow.addLayerObjectsFromStockSet(objects, requestedStocks, exposures, request);

        assertThat(metrics.requestedStockCount()).isEqualTo(size);
        assertThat(metrics.exposureRowsVisited()).isEqualTo(size);
        assertThat(metrics.membershipChecks()).isEqualTo(size);
        assertThat(metrics.matchedExposureRows()).isEqualTo(size);
        assertThat(objects).contains(MARKET, SECTOR);
    }

    private RiskWarningWorkflow workflow(InMemoryRepository repository, RiskDataProvider provider) {
        return workflow(repository, provider, defaultEvaluator());
    }

    private RiskWarningWorkflow workflow(
            InMemoryRepository repository,
            RiskDataProvider provider,
            RiskSnapshotEvaluator evaluator
    ) {
        return workflow(repository, provider, evaluator, 200);
    }

    private RiskWarningWorkflow workflow(
            InMemoryRepository repository,
            RiskDataProvider provider,
            RiskSnapshotEvaluator evaluator,
            int requestObjectLimit
    ) {
        RiskEvidenceAssembler assembler = (object, horizon, tradeDate, asOf, observations) ->
                observations.stream()
                        .filter(observation -> observation.object().equals(object))
                        .filter(observation -> observation.horizon() == horizon)
                        .filter(observation -> observation.tradeDate().equals(tradeDate))
                        .map(observation -> new RiskEvidence(
                                observation.dimension(), observation.indicatorCode(),
                                observation.value().min(new BigDecimal("100")),
                                observation.value(), observation.observedAt(), observation.availableAt(),
                                observation.source(), observation.qualityStatus(), Map.of(
                                        "tradeDate", observation.tradeDate().toString(),
                                        "extremeCandidate",
                                        observation.dimension() == RiskDimension.LOCAL_CONFIRMATION
                                                || observation.dimension() == RiskDimension.FORCED_SELLING)))
                        .toList();
        return new RiskWarningWorkflow(
                List.of(provider), repository, assembler, evaluator, new ShadowRiskGate(), requestObjectLimit);
    }

    private RiskSnapshotEvaluator defaultEvaluator() {
        return request -> {
            if (request.evidence().isEmpty()) {
                return new RiskScoreResult(new RiskSnapshot(
                        request.object(), request.horizon(), request.tradeDate(),
                        null, null, null, null, null, BigDecimal.ONE,
                        null, null, null, new BigDecimal("0.00"), null,
                        List.of(), request.modelVersion(), request.asOf()), List.of("DATA_INSUFFICIENT"));
            }
            RiskSnapshot snapshot = new RiskSnapshot(
                    request.object(), request.horizon(), request.tradeDate(),
                    new BigDecimal("90"), new BigDecimal("90"), new BigDecimal("90"),
                    new BigDecimal("90"), new BigDecimal("90"), BigDecimal.ONE,
                    new BigDecimal("90"), RiskLevel.CRITICAL, RiskStage.STAMPEDE,
                    new BigDecimal("0.90"), new BigDecimal("0.90"),
                    request.evidence(), request.modelVersion(), request.asOf());
            return new RiskScoreResult(snapshot, List.of());
        };
    }

    private RiskProviderBatch availableBatch() {
        RiskObservation eligible = observation("V1", DATE, AS_OF.minusHours(2));
        RiskEvent eligibleEvent = event("event-1", AS_OF.minusHours(1));
        return new RiskProviderBatch(
                "source-a", List.of(eligible), List.of(eligibleEvent),
                new RiskIngestionCheckpoint("dataset-a", "stock:600519.SH", "cursor-2", AS_OF),
                RiskDataQualityStatus.AVAILABLE, null, AS_OF);
    }

    private RiskProviderBatch layeredAvailableBatch() {
        return new RiskProviderBatch(
                "source-a", List.of(
                observation(MARKET, "V1", new BigDecimal("10")),
                observation(SECTOR, "V2", new BigDecimal("10")),
                observation(STOCK, "V3", new BigDecimal("10"))),
                List.of(event("event-1", AS_OF.minusHours(1))),
                new RiskIngestionCheckpoint("dataset-a", "all", "cursor-2", AS_OF),
                RiskDataQualityStatus.AVAILABLE, null, AS_OF);
    }

    private void addExposure(InMemoryRepository repository) {
        repository.exposures.add(new IndustryExposure(
                STOCK, SECTOR, DATE.minusYears(1), null, DATE.atStartOfDay(), DATE.atStartOfDay(),
                "source-a", RiskDataQualityStatus.AVAILABLE));
    }

    private IndustryExposure exposure(
            RiskObjectKey sector,
            LocalDate validFrom,
            LocalDate validTo,
            LocalDateTime observedAt,
            LocalDateTime availableAt,
            RiskDataQualityStatus qualityStatus
    ) {
        return new IndustryExposure(
                STOCK, sector, validFrom, validTo, observedAt, availableAt, "aktools", qualityStatus);
    }

    private RiskCollectionTask task(MarketDatasetCode dataset, List<RiskObjectKey> objects) {
        return new RiskCollectionTask(
                MarketRiskDataProvider.PROVIDER_CODE,
                dataset.code(),
                "test:" + dataset.code() + ":" + objects.getFirst().objectId(),
                objects);
    }

    private RiskObservation observation(String code, LocalDate date, LocalDateTime availableAt) {
        return new RiskObservation(
                STOCK, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                code, new BigDecimal("10"), "ratio", availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }

    private RiskObservation observation(RiskObjectKey object, String code, BigDecimal value) {
        return new RiskObservation(
                object, RiskHorizon.SHORT_TERM, DATE, RiskDimension.STRUCTURAL_FRAGILITY,
                code, value, "score", AS_OF.minusHours(2), AS_OF.minusHours(1),
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }

    private RiskObservation observation(
            RiskObjectKey object,
            String code,
            LocalDate date,
            LocalDateTime availableAt
    ) {
        return new RiskObservation(
                object, RiskHorizon.SHORT_TERM, date, RiskDimension.STRUCTURAL_FRAGILITY,
                code, new BigDecimal("10"), "ratio", availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }

    private RiskObservation confirmationObservation(
            RiskHorizon horizon,
            LocalDate date,
            RiskDimension dimension,
            String indicatorCode,
            BigDecimal value
    ) {
        return new RiskObservation(
                MARKET, horizon, date, dimension, indicatorCode, value, "score",
                date.atTime(18, 0), date.atTime(19, 0), "market-confirmation",
                RiskDataQualityStatus.AVAILABLE, confirmationAttributes(indicatorCode));
    }

    private Map<String, Object> confirmationAttributes(String indicatorCode) {
        if ("C1".equals(indicatorCode)) {
            return Map.of(
                    "metric", "leaderRelativeReturn",
                    "pointInTime", true,
                    "datasetCode", "market_daily",
                    "tradingDay", true,
                    "marketPrice", true);
        }
        return Map.of("pointInTime", true, "datasetCode", "etf_fund_flow");
    }

    private RiskEvent event(String key, LocalDateTime availableAt) {
        return new RiskEvent(
                STOCK, DATE, RiskDimension.SUBSTANTIVE_TRIGGER, "announcement", key,
                new BigDecimal("80"), availableAt.minusHours(1), availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, Map.of());
    }

    private RiskEvent event(
            RiskObjectKey object,
            LocalDate date,
            String key,
            LocalDateTime availableAt,
            Map<String, Object> payload
    ) {
        return new RiskEvent(
                object, date, RiskDimension.SUBSTANTIVE_TRIGGER, "announcement", key,
                new BigDecimal("80"), availableAt.minusHours(1), availableAt.minusMinutes(1), availableAt,
                "source-a", RiskDataQualityStatus.AVAILABLE, payload);
    }

    private static final class RequestCapturingEvaluator implements RiskSnapshotEvaluator {
        private RiskScoreRequest request;

        @Override
        public RiskScoreResult evaluate(RiskScoreRequest request) {
            this.request = request;
            return incomplete(request);
        }
    }

    private static final class LayerCapturingEvaluator implements RiskSnapshotEvaluator {
        private RiskLayerScoreRequest layerRequest;

        @Override
        public RiskScoreResult evaluate(RiskScoreRequest request) {
            BigDecimal score = switch (request.object().objectType()) {
                case MARKET -> new BigDecimal("20");
                case SECTOR -> new BigDecimal("40");
                case STOCK -> new BigDecimal("80");
            };
            BigDecimal modifier = switch (request.object().objectType()) {
                case MARKET -> new BigDecimal("0.90");
                case SECTOR -> new BigDecimal("1.10");
                case STOCK -> new BigDecimal("1.00");
            };
            return formal(request, score, modifier);
        }

        @Override
        public RiskScoreResult evaluateLayers(RiskLayerScoreRequest request) {
            this.layerRequest = request;
            RiskSnapshot snapshot = new RiskSnapshot(
                    request.object(), request.horizon(), request.tradeDate(),
                    request.composition().vScore(), request.composition().tScore(), request.composition().sScore(),
                    request.composition().cScore(), request.composition().aScore(), request.composition().mScore(),
                    new BigDecimal("51"), RiskLevel.WARNING, RiskStage.REPRICING,
                    request.composition().coverage(), request.composition().riskConfidence(),
                    request.composition().evidence(), request.modelVersion(), request.asOf());
            return new RiskScoreResult(snapshot, List.of());
        }

        private RiskScoreResult formal(RiskScoreRequest request, BigDecimal score, BigDecimal modifier) {
            RiskSnapshot snapshot = new RiskSnapshot(
                    request.object(), request.horizon(), request.tradeDate(),
                    score, score, score, score, score, modifier, score,
                    RiskLevel.WARNING, RiskStage.REPRICING, BigDecimal.ONE, BigDecimal.ONE,
                    request.evidence(), request.modelVersion(), request.asOf());
            return new RiskScoreResult(snapshot, List.of());
        }
    }

    private static final class ProductionPathCapturingEvaluator implements RiskSnapshotEvaluator {
        private final List<RiskLayerScoreRequest> layerRequests = new ArrayList<>();

        @Override
        public RiskScoreResult evaluate(RiskScoreRequest request) {
            BigDecimal score = request.evidence().stream()
                    .map(RiskEvidence::score)
                    .filter(java.util.Objects::nonNull)
                    .max(BigDecimal::compareTo)
                    .orElse(new BigDecimal("80"));
            RiskSnapshot snapshot = new RiskSnapshot(
                    request.object(), request.horizon(), request.tradeDate(),
                    score, score, score, score, score, request.timeCorrectionFactor(), score,
                    RiskLevel.WARNING, RiskStage.REPRICING, BigDecimal.ONE, BigDecimal.ONE,
                    request.evidence(), request.modelVersion(), request.asOf());
            return new RiskScoreResult(snapshot, List.of());
        }

        @Override
        public RiskScoreResult evaluateLayers(RiskLayerScoreRequest request) {
            layerRequests.add(request);
            RiskSnapshot snapshot = new RiskSnapshot(
                    request.object(), request.horizon(), request.tradeDate(),
                    request.composition().vScore(), request.composition().tScore(), request.composition().sScore(),
                    request.composition().cScore(), request.composition().aScore(), request.composition().mScore(),
                    new BigDecimal("90"), RiskLevel.CRITICAL, RiskStage.STAMPEDE,
                    request.composition().coverage(), request.composition().riskConfidence(),
                    request.composition().evidence(), request.modelVersion(), request.asOf());
            return new RiskScoreResult(snapshot, List.of());
        }
    }

    private static final class DelegatingCapturingEvaluator implements RiskSnapshotEvaluator {
        private final DefaultRiskSnapshotEvaluator delegate =
                new DefaultRiskSnapshotEvaluator(new RiskScoringEngine());
        private final Map<RiskObjectKey, RiskScoreRequest> rawRequests = new LinkedHashMap<>();

        @Override
        public RiskScoreResult evaluate(RiskScoreRequest request) {
            rawRequests.put(request.object(), request);
            return delegate.evaluate(request);
        }

        @Override
        public RiskScoreResult evaluateLayers(RiskLayerScoreRequest request) {
            return delegate.evaluateLayers(request);
        }
    }

    private static RiskScoreResult incomplete(RiskScoreRequest request) {
        return new RiskScoreResult(new RiskSnapshot(
                request.object(), request.horizon(), request.tradeDate(),
                null, null, null, null, null, BigDecimal.ONE,
                null, null, null, BigDecimal.ZERO, null,
                List.of(), request.modelVersion(), request.asOf()), List.of("DATA_INSUFFICIENT"));
    }

    private static final class CapturingProvider implements RiskDataProvider {
        private final RiskProviderBatch batch;
        private final List<RiskProviderRequest> requests = new ArrayList<>();

        private CapturingProvider(RiskProviderBatch batch) {
            this.batch = batch;
        }

        @Override
        public String providerCode() {
            return "provider-a";
        }

        @Override
        public boolean supports(String datasetCode) {
            return "dataset-a".equals(datasetCode);
        }

        @Override
        public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
            requests.add(request);
            return batch;
        }

        private List<RiskProviderRequest> requests() {
            return requests;
        }
    }

    private static final class TwoStageMarketProvider implements RiskDataProvider {
        private final List<IndustryExposure> memberships;
        private final boolean membershipUnavailable;
        private final Map<MarketDatasetCode, List<RiskProviderRequest>> requests = new LinkedHashMap<>();

        private TwoStageMarketProvider(
                List<IndustryExposure> memberships,
                boolean membershipUnavailable
        ) {
            this.memberships = List.copyOf(memberships);
            this.membershipUnavailable = membershipUnavailable;
        }

        @Override
        public String providerCode() {
            return MarketRiskDataProvider.PROVIDER_CODE;
        }

        @Override
        public boolean supports(String datasetCode) {
            return datasetCode.equals(MarketDatasetCode.SW1_MEMBERSHIP.code())
                    || datasetCode.equals(MarketDatasetCode.MARKET_DAILY.code())
                    || datasetCode.equals(MarketDatasetCode.VALUATION.code());
        }

        @Override
        public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
            MarketDatasetCode dataset = MarketDatasetCode.fromCode(datasetCode);
            requests.computeIfAbsent(dataset, ignored -> new ArrayList<>()).add(request);
            if (dataset == MarketDatasetCode.SW1_MEMBERSHIP) {
                if (membershipUnavailable) {
                    return RiskProviderBatch.unavailable("aktools", "membership unavailable", AS_OF);
                }
                return new RiskProviderBatch(
                        "aktools", List.of(), List.of(), memberships, null,
                        RiskDataQualityStatus.AVAILABLE, null, AS_OF);
            }
            List<RiskObservation> observations = request.objects().stream()
                    .map(object -> new RiskObservation(
                            object, RiskHorizon.SHORT_TERM, DATE,
                            RiskDimension.STRUCTURAL_FRAGILITY,
                            dataset.code() + ":" + object.objectType().getCode(),
                            switch (object.objectType()) {
                                case MARKET -> new BigDecimal("20");
                                case SECTOR -> new BigDecimal("40");
                                case STOCK -> new BigDecimal("80");
                            },
                            "score", AS_OF.minusHours(2), AS_OF.minusHours(1),
                            "aktools", RiskDataQualityStatus.AVAILABLE, Map.of()))
                    .toList();
            return new RiskProviderBatch(
                    "aktools", observations, List.of(), List.of(), null,
                    RiskDataQualityStatus.AVAILABLE, null, AS_OF);
        }

        private RiskProviderRequest stockRequest(MarketDatasetCode dataset) {
            return requests.getOrDefault(dataset, List.of()).stream()
                    .filter(request -> request.objects().stream()
                            .anyMatch(object -> object.objectType() == RiskObjectType.STOCK))
                    .findFirst().orElseThrow();
        }

        private List<RiskProviderRequest> requests(MarketDatasetCode dataset) {
            return List.copyOf(requests.getOrDefault(dataset, List.of()));
        }
    }

    private static class InMemoryRepository implements RiskWorkflowRepository {
        private final Map<String, RiskObservation> observations = new LinkedHashMap<>();
        private final Map<String, RiskEvent> events = new LinkedHashMap<>();
        private final Map<String, StoredRiskSnapshot> snapshots = new LinkedHashMap<>();
        private final Map<String, RiskEvidence> evidence = new LinkedHashMap<>();
        private final Map<String, ShadowGateResult> gates = new LinkedHashMap<>();
        private final Map<String, RiskIngestionCheckpoint> checkpoints = new LinkedHashMap<>();
        private final List<RiskProviderBatch> ingestionStatuses = new ArrayList<>();
        private final List<IndustryExposure> exposures = new ArrayList<>();
        private int observationReads;
        private int eventReads;
        private int exposureReads;
        private int historyReads;
        private long nextSnapshotId = 1;

        @Override
        public Optional<RiskIngestionCheckpoint> findCheckpoint(String providerCode, String datasetCode, String scopeKey) {
            return Optional.ofNullable(checkpoints.get(providerCode + ":" + datasetCode + ":" + scopeKey));
        }

        @Override
        public void saveObservation(RiskObservation observation) {
            observations.put(observation.object() + ":" + observation.horizon() + ":"
                    + observation.tradeDate() + ":" + observation.indicatorCode() + ":"
                    + observation.componentCode() + ":" + observation.source(), observation);
        }

        @Override
        public void saveEvent(RiskEvent event) {
            events.put(event.source() + ":" + event.eventType() + ":" + event.eventKey() + ":" + event.object(), event);
        }

        @Override
        public void saveIndustryExposure(IndustryExposure exposure) {
            String key = exposure.stock() + ":" + exposure.sector() + ":"
                    + exposure.validFrom() + ":" + exposure.source();
            exposures.removeIf(existing -> (existing.stock() + ":" + existing.sector() + ":"
                    + existing.validFrom() + ":" + existing.source()).equals(key));
            exposures.add(exposure);
        }

        @Override
        public void saveCheckpoint(String providerCode, String datasetCode, String scopeKey,
                                   RiskIngestionCheckpoint checkpoint, RiskProviderBatch batch) {
            checkpoints.put(providerCode + ":" + datasetCode + ":" + scopeKey, checkpoint);
        }

        @Override
        public void saveIngestionStatus(String providerCode, String datasetCode, String scopeKey,
                                        RiskIngestionCheckpoint currentCheckpoint, RiskProviderBatch batch) {
            ingestionStatuses.add(batch);
        }

        @Override
        public List<RiskObservation> findObservations(RiskWorkflowRequest request) {
            observationReads++;
            return List.copyOf(observations.values());
        }

        @Override
        public List<RiskEvent> findEvents(RiskWorkflowRequest request) {
            eventReads++;
            return List.copyOf(events.values());
        }

        @Override
        public List<IndustryExposure> findIndustryExposures(RiskWorkflowRequest request) {
            exposureReads++;
            return List.copyOf(exposures);
        }

        @Override
        public List<RiskSnapshot> findSnapshotHistory(RiskWorkflowRequest request) {
            historyReads++;
            return snapshots.values().stream().map(StoredRiskSnapshot::snapshot).toList();
        }

        @Override
        public StoredRiskSnapshot saveSnapshot(RiskSnapshot snapshot, RiskDataQualityStatus qualityStatus,
                                                LocalDateTime observedAt, LocalDateTime availableAt) {
            String key = snapshot.object() + ":" + snapshot.horizon() + ":" + snapshot.tradeDate()
                    + ":" + snapshot.modelVersion();
            StoredRiskSnapshot existing = snapshots.get(key);
            StoredRiskSnapshot replacement = new StoredRiskSnapshot(
                    existing == null ? nextSnapshotId++ : existing.id(),
                    snapshot, observedAt, availableAt, qualityStatus);
            snapshots.put(key, replacement);
            return replacement;
        }

        @Override
        public void replaceEvidence(long snapshotId, RiskObjectKey snapshotObject, List<RiskEvidence> items) {
            evidence.keySet().removeIf(key -> key.startsWith(snapshotId + ":"));
            items.forEach(item -> evidence.put(
                    snapshotId + ":" + item.indicatorCode() + ":" + item.source(), item));
        }

        @Override
        public void deleteGate(RiskSignalCandidate signal, String modelVersion) {
            gates.remove(signal.signalReference() + ":" + signal.horizon() + ":" + modelVersion);
        }

        @Override
        public void saveGate(StoredRiskSnapshot snapshot, ShadowGateResult result,
                             LocalDateTime observedAt, LocalDateTime availableAt) {
            gates.put(result.signalReference() + ":" + result.decision().horizon() + ":"
                    + result.decision().modelVersion(), result);
        }
    }

    private static final class PlannerMarketFixtureProvider implements RiskDataProvider {
        private final List<RiskObservation> observations;

        private PlannerMarketFixtureProvider(List<RiskObservation> observations) {
            this.observations = observations;
        }

        @Override
        public String providerCode() {
            return MarketRiskDataProvider.PROVIDER_CODE;
        }

        @Override
        public boolean supports(String datasetCode) {
            return true;
        }

        @Override
        public RiskProviderBatch fetch(String datasetCode, RiskProviderRequest request) {
            if (MarketDatasetCode.CN_A_STOCK_MASTER.code().equals(datasetCode)) {
                return new RiskProviderBatch(
                        "fixture-market", observations, List.of(), List.of(), null,
                        RiskDataQualityStatus.AVAILABLE, null, AS_OF);
            }
            if (MarketDatasetCode.SW1_MEMBERSHIP.code().equals(datasetCode)) {
                IndustryExposure exposure = new IndustryExposure(
                        STOCK, SECTOR, DATE.minusYears(5), null,
                        AS_OF.minusHours(2), AS_OF.minusHours(1), "fixture-market",
                        RiskDataQualityStatus.AVAILABLE);
                return new RiskProviderBatch(
                        "fixture-market", List.of(), List.of(), List.of(exposure), null,
                        RiskDataQualityStatus.AVAILABLE, null, AS_OF);
            }
            return RiskProviderBatch.validZero("fixture-market", null, AS_OF);
        }
    }

    private static final class ExposureJdbcRepository extends InMemoryRepository {
        private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:risk_first_run_exposure;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", ""));
        private final JdbcRiskWorkflowRepository exposureRepository;

        private ExposureJdbcRepository() {
            jdbc.execute("DROP ALL OBJECTS");
            jdbc.execute("""
                    CREATE TABLE risk_object_exposure (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        object_type VARCHAR(16), object_id VARCHAR(64),
                        parent_object_type VARCHAR(16), parent_object_id VARCHAR(64),
                        exposure_weight DECIMAL(8, 6), valid_from DATE, valid_to DATE,
                        observed_at TIMESTAMP, available_at TIMESTAMP,
                        source VARCHAR(64), quality_status VARCHAR(32), metadata_json VARCHAR(1024),
                        UNIQUE(object_type, object_id, parent_object_type, parent_object_id, valid_from, source))
                    """);
            exposureRepository = new JdbcRiskWorkflowRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public void saveIndustryExposure(IndustryExposure exposure) {
            super.saveIndustryExposure(exposure);
            exposureRepository.saveIndustryExposure(exposure);
        }

        private int persistedExposureCount() {
            return jdbc.queryForObject("SELECT COUNT(*) FROM risk_object_exposure", Integer.class);
        }
    }
}
