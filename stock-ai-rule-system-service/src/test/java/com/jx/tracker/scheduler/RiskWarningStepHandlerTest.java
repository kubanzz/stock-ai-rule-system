package com.jx.tracker.scheduler;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.risk.workflow.RiskAfterCloseWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RiskWarningStepHandlerTest {

    @Test
    void runsAfterSignalGenerationAndRepeatedExecutionDelegatesToIdempotentWorkflow() {
        AtomicInteger calls = new AtomicInteger();
        RiskAfterCloseWorkflow workflow = (tradeDate, symbols) -> {
            calls.incrementAndGet();
            return new RiskWorkflowRunSummary(2, 1, 3, 4, 1, 2, 0);
        };
        RiskWarningStepHandler handler = new RiskWarningStepHandler(Optional.of(workflow));
        DailyWorkflowContext context = context();

        var first = handler.execute(context);
        var second = handler.execute(context);

        assertThat(WorkflowStepCode.orderedSteps()).containsSubsequence(
                WorkflowStepCode.SIGNAL_GENERATION, WorkflowStepCode.RISK_WARNING,
                WorkflowStepCode.HISTORICAL_VERIFICATION);
        assertThat(first.getStatus()).isEqualTo("success");
        assertThat(first.getDetails()).containsEntry("gateCount", 1).containsEntry("shadowMode", true);
        assertThat(second.getStatus()).isEqualTo("success");
        assertThat(calls).hasValue(2);
    }

    @Test
    void missingIntegrationCompositionIsAnOptionalSkip() {
        var result = new RiskWarningStepHandler(Optional.empty()).execute(context());

        assertThat(result.getStatus()).isEqualTo("skipped");
        assertThat(result.getDetails()).containsEntry("optional", true).containsEntry("shadowMode", true);
    }

    private DailyWorkflowContext context() {
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setTradeDate(LocalDate.of(2026, 7, 18));
        request.setSymbols(List.of("600519.SH"));
        request.setDryRun(false);
        return new DailyWorkflowContext("run-1", request, WorkflowTriggerType.SCHEDULED);
    }
}
