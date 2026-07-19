package com.jx.tracker.risk.workflow;

import java.time.LocalDate;
import java.util.List;

/** 集成线负责提供实际 Provider、对象计划与信号查询组合。 */
@FunctionalInterface
public interface RiskAfterCloseWorkflow {

    RiskWorkflowRunSummary runDaily(LocalDate tradeDate, List<String> symbols);
}
