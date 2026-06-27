package com.jx.tracker.domain.vo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class DailyWorkflowRunResultVo {

    private String runId;

    private String triggerType;

    private LocalDate tradeDate;

    private List<String> symbols;

    private Boolean dryRun;

    private String status;

    private String riskDisclaimer;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private List<DailyWorkflowStepResultVo> steps;

    private List<DailyWorkflowDependencyVo> integrationDependencies;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public LocalDate getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(LocalDate tradeDate) {
        this.tradeDate = tradeDate;
    }

    public List<String> getSymbols() {
        return symbols;
    }

    public void setSymbols(List<String> symbols) {
        this.symbols = symbols;
    }

    public Boolean getDryRun() {
        return dryRun;
    }

    public void setDryRun(Boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRiskDisclaimer() {
        return riskDisclaimer;
    }

    public void setRiskDisclaimer(String riskDisclaimer) {
        this.riskDisclaimer = riskDisclaimer;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public List<DailyWorkflowStepResultVo> getSteps() {
        return steps;
    }

    public void setSteps(List<DailyWorkflowStepResultVo> steps) {
        this.steps = steps;
    }

    public List<DailyWorkflowDependencyVo> getIntegrationDependencies() {
        return integrationDependencies;
    }

    public void setIntegrationDependencies(List<DailyWorkflowDependencyVo> integrationDependencies) {
        this.integrationDependencies = integrationDependencies;
    }
}
