package com.jx.tracker.scheduler;

import com.jx.tracker.risk.workflow.RiskAfterCloseWorkflow;
import com.jx.tracker.risk.workflow.RiskWorkflowRunSummary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

@Component
public class RiskWarningStepHandler implements DailyWorkflowStepHandler {

    private final Optional<RiskAfterCloseWorkflow> workflow;

    public RiskWarningStepHandler(Optional<RiskAfterCloseWorkflow> workflow) {
        this.workflow = workflow;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.RISK_WARNING;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        if (workflow.isEmpty()) {
            return DailyWorkflowStepResults.skipped(
                    stepCode(), startedAt,
                    "风险 Provider 与对象计划尚未由集成线接入。",
                    Map.of("optional", true, "shadowMode", true));
        }
        RiskWorkflowRunSummary summary = workflow.orElseThrow().runDaily(
                context.getRequest().getTradeDate(), context.getRequest().getSymbols());
        return DailyWorkflowStepResults.success(
                stepCode(), startedAt, "盘后风险评分与影子闸门完成。",
                Map.of(
                        "observationCount", summary.observationCount(),
                        "eventCount", summary.eventCount(),
                        "snapshotCount", summary.snapshotCount(),
                        "evidenceCount", summary.evidenceCount(),
                        "gateCount", summary.gateCount(),
                        "checkpointCount", summary.checkpointCount(),
                        "unavailableDatasetCount", summary.unavailableDatasetCount(),
                        "shadowMode", true));
    }
}
