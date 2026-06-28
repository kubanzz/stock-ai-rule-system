package com.jx.tracker.scheduler;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class RuleInferenceStepHandler implements DailyWorkflowStepHandler {

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.RULE_INFERENCE;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        return DailyWorkflowStepResults.success(
                stepCode(),
                LocalDateTime.now(),
                "JSON 规则推理由信号生成步骤按因子快照执行。",
                Map.of("delegatedTo", WorkflowStepCode.SIGNAL_GENERATION.getCode())
        );
    }
}
