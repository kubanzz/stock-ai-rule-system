package com.jx.tracker.risk.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.risk.engine.RiskScoreRequest;
import com.jx.tracker.risk.engine.RiskScoreResult;
import com.jx.tracker.risk.engine.RiskLayerScoreRequest;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.data.flow.FlowEventRiskDataProvider;
import com.jx.tracker.risk.data.flow.AkToolsFlowEventSourceClient;
import com.jx.tracker.risk.data.flow.FlowEventSourceBatch;
import com.jx.tracker.risk.data.flow.FlowEventSourceRecord;
import com.jx.tracker.risk.data.market.IndustryExposure;
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
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

        assertThat(request.collectionStartDate()).isEqualTo(DATE.minusYears(5));
        assertThat(request.scoreStartDate()).isEqualTo(DATE);
        assertThat(provider.requests()).hasSize(2);
        assertThat(provider.requests().get(0).checkpoint()).isNull();
        assertThat(provider.requests().get(1).checkpoint()).isNotNull();
        assertThat(repository.observations).hasSize(3);
        assertThat(repository.events).hasSize(1);
        assertThat(repository.snapshots).isNotEmpty();
        assertThat(repository.evidence).hasSize(5);
        assertThat(repository.gates).hasSize(1);
        assertThat(repository.checkpoints).hasSize(1);
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
        assertThat(backfill.collectionStartDate()).isEqualTo(DATE.minusYears(5));
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
                    .containsEntry("sourceRecordId", "share_reduction:600519.SH:2026-07-18:2026-07-18")
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
            for (int day = 59; day >= 0; day--) {
                LocalDate historyDate = DATE.minusDays(day);
                BigDecimal value = BigDecimal.valueOf(60L - day);
                repository.saveObservation(confirmationObservation(
                        horizon, historyDate, RiskDimension.LOCAL_CONFIRMATION, "C1", value));
                repository.saveObservation(confirmationObservation(
                        horizon, historyDate, RiskDimension.FORCED_SELLING, "A2", value));
            }
        }
        FlowEventSourceRecord reduction = new FlowEventSourceRecord(
                "reduction-prod-1", "cursor-prod-1", STOCK, DATE,
                DATE.atTime(17, 0), DATE.atTime(18, 0), DATE.atTime(19, 0),
                new BigDecimal("100"), "score", "share_reduction", "实际减持",
                Map.of("actualReduction", true, "adverse", true));
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

    private RiskWarningWorkflow workflow(InMemoryRepository repository, RiskDataProvider provider) {
        return workflow(repository, provider, defaultEvaluator());
    }

    private RiskWarningWorkflow workflow(
            InMemoryRepository repository,
            RiskDataProvider provider,
            RiskSnapshotEvaluator evaluator
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
                List.of(provider), repository, assembler, evaluator, new ShadowRiskGate());
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
                RiskDataQualityStatus.AVAILABLE, Map.of("pointInTime", true));
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

    private static final class InMemoryRepository implements RiskWorkflowRepository {
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
                    + observation.tradeDate() + ":" + observation.indicatorCode() + ":" + observation.source(), observation);
        }

        @Override
        public void saveEvent(RiskEvent event) {
            events.put(event.source() + ":" + event.eventType() + ":" + event.eventKey() + ":" + event.object(), event);
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
}
