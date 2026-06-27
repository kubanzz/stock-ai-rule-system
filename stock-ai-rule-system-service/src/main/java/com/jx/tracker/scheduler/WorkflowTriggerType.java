package com.jx.tracker.scheduler;

public enum WorkflowTriggerType {

    MANUAL("manual"),
    SCHEDULED("scheduled");

    private final String code;

    WorkflowTriggerType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
