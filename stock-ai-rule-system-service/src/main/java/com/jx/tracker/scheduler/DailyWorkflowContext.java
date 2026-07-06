package com.jx.tracker.scheduler;

import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;

import java.util.HashMap;
import java.util.Map;

public class DailyWorkflowContext {

    private String runId;

    private DailyWorkflowTriggerDto request;

    private WorkflowTriggerType triggerType;

    private final Map<String, Object> attributes = new HashMap<>();

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

    public void putAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public <T> T getAttribute(String key, Class<T> type) {
        Object value = attributes.get(key);
        if (value == null) {
            return null;
        }
        return type.cast(value);
    }
}
