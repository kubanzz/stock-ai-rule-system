package com.jx.tracker.scheduler;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;

public class DailyWorkflowContext {

    private String runId;

    private DailyWorkflowTriggerDto request;

    private WorkflowTriggerType triggerType;

    public DailyWorkflowContext(String runId, DailyWorkflowTriggerDto request, WorkflowTriggerType triggerType) {
        this.runId = runId;
        this.request = request;
        this.triggerType = triggerType;
    }

    public String getRunId() {
        return runId;
    }

    public DailyWorkflowTriggerDto getRequest() {
        return request;
    }

    public WorkflowTriggerType getTriggerType() {
        return triggerType;
    }
}
