package com.jx.tracker.scheduler;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class CandidateRuleBacktestStepHandler implements DailyWorkflowStepHandler {

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.CANDIDATE_RULE_BACKTEST;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        return DailyWorkflowStepResults.skipped(
                stepCode(),
                LocalDateTime.now(),
                "候选规则回测需指定候选编码，请通过 /api/backtests 显式触发。",
                Map.of("optional", true)
        );
    }
}
