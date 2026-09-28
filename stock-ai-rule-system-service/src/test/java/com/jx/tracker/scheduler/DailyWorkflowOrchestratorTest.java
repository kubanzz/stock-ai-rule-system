package com.jx.tracker.scheduler;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.entity.WorkflowRun;
import com.jx.tracker.domain.entity.WorkflowStepRun;
import com.jx.tracker.domain.vo.DailyWorkflowRunResultVo;
import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;
import com.jx.tracker.mapper.WorkflowRunMapper;
import com.jx.tracker.mapper.WorkflowStepRunMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
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
                        "risk_warning",
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

    @Test
    void runDailyWorkflowExecutesFactorStepWhenDryRunDisabled() {
        DailyWorkflowStepHandler factor = mock(DailyWorkflowStepHandler.class);
        when(factor.stepCode()).thenReturn(WorkflowStepCode.FACTOR_CALCULATION);
        DailyWorkflowStepResultVo success = new DailyWorkflowStepResultVo();
        success.setStepCode("factor_calculation");
        success.setStatus("success");
        success.setMessage("因子计算完成");
        when(factor.execute(any())).thenReturn(success);

        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setDryRun(false);
        request.setSymbols(List.of("000001.SZ"));

        DailyWorkflowRunResultVo result = new DailyWorkflowOrchestrator(List.of(factor))
                .runDailyWorkflow(request, WorkflowTriggerType.MANUAL);

        verify(factor).execute(any());
        assertThat(result.getDryRun()).isFalse();
        assertThat(result.getSteps()).filteredOn(step -> "factor_calculation".equals(step.getStepCode()))
                .singleElement().extracting(DailyWorkflowStepResultVo::getStatus)
                .isEqualTo("success");
    }

    @Test
    void runDailyWorkflowReportsSymbolsResolvedByMarketDataStep() {
        DailyWorkflowStepHandler marketData = mock(DailyWorkflowStepHandler.class);
        when(marketData.stepCode()).thenReturn(WorkflowStepCode.MARKET_DATA_COLLECTION);
        when(marketData.execute(any())).thenAnswer(invocation -> {
            DailyWorkflowContext context = invocation.getArgument(0);
            context.getRequest().setSymbols(List.of("000001.SZ", "600519.SH"));
            DailyWorkflowStepResultVo success = new DailyWorkflowStepResultVo();
            success.setStepCode("market_data_sync");
            success.setStatus("success");
            success.setMessage("行情数据同步完成");
            return success;
        });

        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setDryRun(false);

        DailyWorkflowRunResultVo result = new DailyWorkflowOrchestrator(List.of(marketData))
                .runDailyWorkflow(request, WorkflowTriggerType.MANUAL);

        assertThat(result.getSymbols()).containsExactly("000001.SZ", "600519.SH");
    }

    @Test
    void runDailyWorkflowPersistsRequestStepsSummaryAndFailureMessage() {
        WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
        WorkflowStepRunMapper workflowStepRunMapper = mock(WorkflowStepRunMapper.class);
        DailyWorkflowStepHandler marketData = mock(DailyWorkflowStepHandler.class);
        when(marketData.stepCode()).thenReturn(WorkflowStepCode.MARKET_DATA_COLLECTION);
        when(marketData.execute(any())).thenThrow(new IllegalStateException("行情同步失败"));
        when(workflowRunMapper.insert(any(WorkflowRun.class))).thenAnswer(invocation -> {
            invocation.<WorkflowRun>getArgument(0).setId(17L);
            return 1;
        });

        DailyWorkflowOrchestrator orchestrator = new DailyWorkflowOrchestrator(
                List.of(marketData), workflowRunMapper, workflowStepRunMapper);
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setTradeDate(LocalDate.of(2026, 6, 26));
        request.setSymbols(List.of(" 000001.SZ ", "000001.SZ"));
        request.setDryRun(false);

        DailyWorkflowRunResultVo result = orchestrator.runDailyWorkflow(request, WorkflowTriggerType.MANUAL);

        verify(workflowRunMapper).insert(org.mockito.ArgumentMatchers.<WorkflowRun>argThat(run ->
                run.getBizDate().equals(LocalDate.of(2026, 6, 26))
                        && "manual".equals(run.getTriggerType())
                        && Boolean.FALSE.equals(run.getDryRun())
                        && run.getTriggerBy().startsWith("daily-workflow:")
                        && run.getRequestParams().contains("2026-06-26")
                        && run.getRequestParams().contains("000001.SZ")));
        verify(workflowStepRunMapper).insert(org.mockito.ArgumentMatchers.<WorkflowStepRun>argThat(step ->
                Long.valueOf(17L).equals(step.getWorkflowRunId())
                        && "market_data_sync".equals(step.getStepCode())
                        && "failed".equals(step.getStatus())
                        && "行情同步失败".equals(step.getErrorMessage())));
        verify(workflowRunMapper).updateById(org.mockito.ArgumentMatchers.<WorkflowRun>argThat(run ->
                "failed".equals(run.getStatus())
                        && run.getErrorMessage().contains("行情同步失败")
                        && run.getSummary().contains("\"total\"")
                        && run.getSummary().contains("\"failed\"")
                        && run.getSummary().contains("\"skipped\"")));
        assertThat(result.getStatus()).isEqualTo("failed");
    }

    @Test
    void dryRunPersistsSkippedStepsWithoutExecutingHandlers() {
        WorkflowRunMapper workflowRunMapper = mock(WorkflowRunMapper.class);
        WorkflowStepRunMapper workflowStepRunMapper = mock(WorkflowStepRunMapper.class);
        DailyWorkflowStepHandler marketData = mock(DailyWorkflowStepHandler.class);
        when(marketData.stepCode()).thenReturn(WorkflowStepCode.MARKET_DATA_COLLECTION);
        AtomicReference<String> insertedStatus = new AtomicReference<>();
        when(workflowRunMapper.insert(any(WorkflowRun.class))).thenAnswer(invocation -> {
            WorkflowRun run = invocation.getArgument(0);
            insertedStatus.set(run.getStatus());
            run.setId(18L);
            return 1;
        });

        DailyWorkflowOrchestrator orchestrator = new DailyWorkflowOrchestrator(
                List.of(marketData), workflowRunMapper, workflowStepRunMapper);

        DailyWorkflowRunResultVo result = orchestrator.runDailyWorkflow(new DailyWorkflowTriggerDto(), WorkflowTriggerType.MANUAL);

        verify(marketData, never()).execute(any());
        assertThat(insertedStatus).hasValue("running");
        verify(workflowRunMapper).insert(org.mockito.ArgumentMatchers.<WorkflowRun>argThat(run ->
                Boolean.TRUE.equals(run.getDryRun())));
        verify(workflowRunMapper).updateById(org.mockito.ArgumentMatchers.<WorkflowRun>argThat(run ->
                "skipped".equals(run.getStatus())));
        verify(workflowStepRunMapper, org.mockito.Mockito.atLeastOnce()).insert(org.mockito.ArgumentMatchers.<WorkflowStepRun>argThat(step -> "skipped".equals(step.getStatus())));
        assertThat(result.getDryRun()).isTrue();
        assertThat(result.getStatus()).isEqualTo("skipped");
    }
}
