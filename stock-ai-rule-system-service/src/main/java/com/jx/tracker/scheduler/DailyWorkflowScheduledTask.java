package com.jx.tracker.scheduler;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyWorkflowScheduledTask {

    private final DailyWorkflowOrchestrator orchestrator;

    @Value("${stock-ai-rule.scheduler.daily-enabled:false}")
    private boolean dailyEnabled;

    public DailyWorkflowScheduledTask(DailyWorkflowOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${stock-ai-rule.scheduler.daily-cron:-}", zone = "${stock-ai-rule.scheduler.zone:Asia/Shanghai}")
    public void runDailyWorkflow() {
        if (!dailyEnabled) {
            return;
        }
        DailyWorkflowTriggerDto request = new DailyWorkflowTriggerDto();
        request.setDryRun(true);
        orchestrator.runDailyWorkflow(request, WorkflowTriggerType.SCHEDULED);
    }
}
