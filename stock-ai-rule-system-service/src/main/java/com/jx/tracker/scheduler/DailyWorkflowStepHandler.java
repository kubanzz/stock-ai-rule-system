package com.jx.tracker.scheduler;

import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;

public interface DailyWorkflowStepHandler {

    WorkflowStepCode stepCode();

    DailyWorkflowStepResultVo execute(DailyWorkflowContext context);
}
