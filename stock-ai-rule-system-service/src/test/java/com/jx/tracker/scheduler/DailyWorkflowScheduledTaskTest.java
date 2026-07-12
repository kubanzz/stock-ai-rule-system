package com.jx.tracker.scheduler;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DailyWorkflowScheduledTaskTest {

    @Test
    void enabledScheduleRunsTheRealWorkflowInsteadOfDryRun() {
        DailyWorkflowOrchestrator orchestrator = mock(DailyWorkflowOrchestrator.class);
        DailyWorkflowScheduledTask task = new DailyWorkflowScheduledTask(orchestrator);
        ReflectionTestUtils.setField(task, "dailyEnabled", true);

        task.runDailyWorkflow();

        ArgumentCaptor<DailyWorkflowTriggerDto> request = ArgumentCaptor.forClass(DailyWorkflowTriggerDto.class);
        verify(orchestrator).runDailyWorkflow(request.capture(), org.mockito.ArgumentMatchers.eq(
                WorkflowTriggerType.SCHEDULED));
        assertThat(request.getValue().getDryRun()).isFalse();
    }
}
