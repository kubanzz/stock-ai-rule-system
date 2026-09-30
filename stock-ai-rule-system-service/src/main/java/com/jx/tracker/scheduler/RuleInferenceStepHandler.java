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
                "生产 Drools 规则由信号生成步骤按因子快照执行；JSON 只用于候选规则回测与发布前编译。",
                Map.of("delegatedTo", WorkflowStepCode.SIGNAL_GENERATION.getCode())
        );
    }
}
