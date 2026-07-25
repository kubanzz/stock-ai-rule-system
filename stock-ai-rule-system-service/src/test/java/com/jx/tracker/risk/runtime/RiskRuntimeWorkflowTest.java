package com.jx.tracker.risk.runtime;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.risk.data.market.MarketDatasetCode;
import com.jx.tracker.risk.workflow.RiskWarningWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRequest;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import com.jx.tracker.scheduler.DailyWorkflowContext;
import com.jx.tracker.scheduler.RiskWarningStepHandler;
import com.jx.tracker.scheduler.WorkflowTriggerType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RiskRuntimeWorkflowTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);
    private static final RiskWorkflowRunSummary SUMMARY =
            new RiskWorkflowRunSummary(2, 1, 3, 4, 1, 2, 0);

    @Test
    void dailyWorkflowBuildsPointInTimeRequestAndMakesSchedulerHandlerNonSkipped() {
        RiskSignalCandidateReader candidateReader = mock(RiskSignalCandidateReader.class);
        RiskWarningWorkflow coreWorkflow = mock(RiskWarningWorkflow.class);
        when(candidateReader.read(any(), any(), any(), any())).thenReturn(List.of());
        when(coreWorkflow.run(any())).thenReturn(SUMMARY);
        RiskWarningProperties properties = properties(false);
        DefaultRiskAfterCloseWorkflow afterCloseWorkflow = new DefaultRiskAfterCloseWorkflow(
                planner(), candidateReader, coreWorkflow, clockAt(DATE.atTime(21, 0)), properties);

        var result = new RiskWarningStepHandler(Optional.of(afterCloseWorkflow))
                .execute(context(List.of("sh600519")));

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getDetails()).containsEntry("gateCount", 1).containsEntry("shadowMode", true);
        ArgumentCaptor<RiskWorkflowRequest> requestCaptor = ArgumentCaptor.forClass(RiskWorkflowRequest.class);
        verify(coreWorkflow).run(requestCaptor.capture());
        RiskWorkflowRequest request = requestCaptor.getValue();
        assertThat(request.scoreStartDate()).isEqualTo(DATE);
        assertThat(request.asOf()).isEqualTo(DATE.atTime(19, 0));
        assertThat(request.afterCloseCutoff()).isEqualTo(LocalTime.of(19, 0));
        assertThat(request.modelVersion()).isEqualTo("risk-runtime-v1");
        assertThat(request.collectionTasks()).hasSize(13);
        verify(candidateReader).read(
                DATE,
                request.collectionTasks().getFirst().objects(),
                request.horizons(),
                DATE.atTime(19, 0)
        );
    }

    @Test
    void backfillDisabledIsANoOpEvenWhenExplicitlyCalled() {
        RiskSignalCandidateReader candidateReader = mock(RiskSignalCandidateReader.class);
        RiskWarningWorkflow coreWorkflow = mock(RiskWarningWorkflow.class);
        RiskBackfillService service = new RiskBackfillService(
                planner(), candidateReader, coreWorkflow, clockAt(DATE.atTime(21, 0)), properties(false));

        Optional<RiskWorkflowRunSummary> result = service.runFiveYearBackfill(DATE, List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(candidateReader, coreWorkflow);
    }

    @Test
    void manualMarketSyncUsesTheRealCurrentAsOfAndMarketPlan() {
        RiskSignalCandidateReader candidateReader = mock(RiskSignalCandidateReader.class);
        RiskWarningWorkflow coreWorkflow = mock(RiskWarningWorkflow.class);
        when(coreWorkflow.run(any())).thenReturn(SUMMARY);
        DefaultRiskAfterCloseWorkflow workflow = new DefaultRiskAfterCloseWorkflow(
                planner(), candidateReader, coreWorkflow, clockAt(DATE.plusDays(1).atTime(10, 0)),
                properties(false));

        RiskWorkflowRunSummary result = workflow.runManualMarket(DATE);

        assertThat(result).isEqualTo(SUMMARY);
        ArgumentCaptor<RiskWorkflowRequest> requestCaptor =
                ArgumentCaptor.forClass(RiskWorkflowRequest.class);
        verify(coreWorkflow).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(request -> {
            assertThat(request.asOf()).isEqualTo(DATE.plusDays(1).atTime(10, 0));
            assertThat(request.collectionStartDate()).isEqualTo(DATE.minusYears(6));
            assertThat(request.providerStartDate()).isEqualTo(DATE.minusYears(2));
            assertThat(request.providerResultStartDate()).isEqualTo(DATE);
            assertThat(request.collectionTasks()).hasSize(5);
            assertThat(request.collectionTasks()).noneMatch(task ->
                    task.datasetCode().equals(MarketDatasetCode.BREADTH.code()));
            assertThat(request.collectionTasks()).noneMatch(task ->
                    task.datasetCode().equals(MarketDatasetCode.CROSS_MARKET.code()));
            assertThat(request.collectionTasks().stream()
                    .flatMap(task -> task.objects().stream()))
                    .anyMatch(object -> object.objectId().equals("CN-A"));
        });
        verifyNoInteractions(candidateReader);
    }

    @Test
    void explicitlyEnabledBackfillScoresTheFullFiveYearWindow() {
        RiskSignalCandidateReader candidateReader = mock(RiskSignalCandidateReader.class);
        RiskWarningWorkflow coreWorkflow = mock(RiskWarningWorkflow.class);
        when(candidateReader.read(any(), any(), any(), any())).thenReturn(List.of());
        when(coreWorkflow.run(any())).thenReturn(SUMMARY);
        RiskBackfillService service = new RiskBackfillService(
                planner(), candidateReader, coreWorkflow, clockAt(DATE.atTime(21, 0)), properties(true));

        Optional<RiskWorkflowRunSummary> result = service.runFiveYearBackfill(DATE, List.of());

        assertThat(result).contains(SUMMARY);
        ArgumentCaptor<RiskWorkflowRequest> requestCaptor = ArgumentCaptor.forClass(RiskWorkflowRequest.class);
        verify(coreWorkflow).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).satisfies(request -> {
            assertThat(request.collectionStartDate()).isEqualTo(DATE.minusYears(11));
            assertThat(request.scoreStartDate()).isEqualTo(DATE.minusYears(5));
            assertThat(request.endDate()).isEqualTo(DATE);
            assertThat(request.asOf()).isEqualTo(DATE.atTime(19, 0));
        });
    }

    private RiskWorkflowPlanner planner() {
        return new RiskWorkflowPlanner(() -> List.of("600519.SH"));
    }

    private RiskWarningProperties properties(boolean backfillEnabled) {
        RiskWarningProperties properties = new RiskWarningProperties();
        properties.setEnabled(true);
        properties.setBackfillEnabled(backfillEnabled);
        properties.setModelVersion("risk-runtime-v1");
        properties.setAfterCloseCutoff(LocalTime.of(19, 0));
        return properties;
    }

    private Clock clockAt(LocalDateTime dateTime) {
        Instant instant = dateTime.atZone(ZONE).toInstant();
        return Clock.fixed(instant, ZONE);
    }

    private DailyWorkflowContext context(List<String> symbols) {
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setTradeDate(DATE);
        request.setSymbols(symbols);
        request.setDryRun(false);
        return new DailyWorkflowContext("run-runtime", request, WorkflowTriggerType.SCHEDULED);
    }
}
