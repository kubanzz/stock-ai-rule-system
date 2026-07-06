package com.jx.tracker.scheduler;

import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;

import java.time.LocalDateTime;
import java.util.Map;

final class DailyWorkflowStepResults {

    private DailyWorkflowStepResults() {
    }

    static DailyWorkflowStepResultVo success(WorkflowStepCode step, LocalDateTime startedAt, String message, Map<String, Object> details) {
        return result(step, startedAt, "success", message, details);
    }

    static DailyWorkflowStepResultVo skipped(WorkflowStepCode step, LocalDateTime startedAt, String message, Map<String, Object> details) {
        return result(step, startedAt, "skipped", message, details);
    }

    static DailyWorkflowStepResultVo failed(WorkflowStepCode step, LocalDateTime startedAt, String message, Map<String, Object> details) {
        return result(step, startedAt, "failed", message, details);
    }

    private static DailyWorkflowStepResultVo result(
            WorkflowStepCode step,
            LocalDateTime startedAt,
            String status,
            String message,
            Map<String, Object> details
    ) {
        DailyWorkflowStepResultVo result = new DailyWorkflowStepResultVo();
        result.setStepCode(step.getCode());
        result.setStepName(step.getName());
        result.setStatus(status);
        result.setMessage(message);
        result.setStartedAt(startedAt);
        result.setFinishedAt(LocalDateTime.now());
        result.setDetails(details == null ? Map.of() : details);
        return result;
    }
}
