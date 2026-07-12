package com.jx.tracker.scheduler;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.vo.DailyWorkflowRunResultVo;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyWorkflowOrchestratorTest {

    @Test
    void runDailyWorkflowReportsOrderedSkippedStepsWhenMilestoneHandlersAreMissing() {
        DailyWorkflowOrchestrator orchestrator = new DailyWorkflowOrchestrator(List.of());
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setTradeDate(LocalDate.of(2026, 6, 26));
        request.setSymbols(List.of("000001.SZ"));
        request.setDryRun(false);

        DailyWorkflowRunResultVo result = orchestrator.runDailyWorkflow(request, WorkflowTriggerType.MANUAL);

        assertThat(result.getStatus()).isEqualTo("partial");
        assertThat(result.getRiskDisclaimer()).isEqualTo(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        assertThat(result.getSteps())
                .extracting("stepCode")
                .containsExactly(
                        "market_data_sync",
                        "factor_calculation",
                        "rule_signal_generation",
                        "prediction_validation",
                        "ai_review",
                        "candidate_rule_backtest"
                );
        assertThat(result.getSteps())
                .allSatisfy(step -> assertThat(step.getStatus()).isEqualTo("skipped"));
        assertThat(result.getIntegrationDependencies())
                .extracting("moduleCode")
                .contains("M1", "M2", "M3", "M4", "M5", "M6");
    }

    @Test
    void runDailyWorkflowDefaultsToDryRunForSafety() {
        DailyWorkflowOrchestrator orchestrator = new DailyWorkflowOrchestrator(List.of());

        DailyWorkflowRunResultVo result = orchestrator.runDailyWorkflow(new DailyWorkflowTriggerDto(), WorkflowTriggerType.MANUAL);

        assertThat(result.getDryRun()).isTrue();
        assertThat(result.getSteps())
                .allSatisfy(step -> assertThat(step.getMessage()).contains("dryRun"));
    }

    @Test
    void runDailyWorkflowSkipsAllDownstreamStepsAfterRequiredStepFails() {
        DailyWorkflowStepHandler marketData = mock(DailyWorkflowStepHandler.class);
        DailyWorkflowStepHandler factor = mock(DailyWorkflowStepHandler.class);
        when(marketData.stepCode()).thenReturn(WorkflowStepCode.MARKET_DATA_COLLECTION);
        when(factor.stepCode()).thenReturn(WorkflowStepCode.FACTOR_CALCULATION);
        when(marketData.execute(any())).thenThrow(new IllegalStateException("行情同步失败"));
        DailyWorkflowOrchestrator orchestrator = new DailyWorkflowOrchestrator(List.of(marketData, factor));
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setDryRun(false);

        DailyWorkflowRunResultVo result = orchestrator.runDailyWorkflow(request, WorkflowTriggerType.SCHEDULED);

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getSteps().getFirst().getStatus()).isEqualTo("failed");
        assertThat(result.getSteps().stream().skip(1))
                .allSatisfy(step -> {
                    assertThat(step.getStatus()).isEqualTo("skipped");
                    assertThat(step.getMessage()).contains("前置步骤失败");
                });
        verify(factor, never()).execute(any());
    }
}
