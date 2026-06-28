package com.jx.tracker.scheduler;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AiReviewWorkflowStepHandler implements DailyWorkflowStepHandler {

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.AI_REVIEW;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        return DailyWorkflowStepResults.skipped(
                stepCode(),
                LocalDateTime.now(),
                "AI 复盘为治理步骤，请通过 /api/ai/review 显式触发。",
                Map.of("optional", true)
        );
    }
}
