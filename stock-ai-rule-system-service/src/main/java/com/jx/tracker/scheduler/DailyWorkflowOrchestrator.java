package com.jx.tracker.scheduler;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.vo.DailyWorkflowDependencyVo;
import com.jx.tracker.domain.vo.DailyWorkflowRunResultVo;
import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;
import com.jx.tracker.domain.entity.WorkflowRun;
import com.jx.tracker.domain.entity.WorkflowStepRun;
import com.jx.tracker.domain.enums.WorkflowRunStatus;
import com.jx.tracker.mapper.WorkflowRunMapper;
import com.jx.tracker.mapper.WorkflowStepRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class DailyWorkflowOrchestrator {

    private final Map<WorkflowStepCode, DailyWorkflowStepHandler> handlers;
    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowStepRunMapper workflowStepRunMapper;
    private final ObjectMapper objectMapper;
    private final boolean persistenceEnabled;

    public DailyWorkflowOrchestrator(List<DailyWorkflowStepHandler> handlers) {
        this.handlers = new EnumMap<>(WorkflowStepCode.class);
        handlers.forEach(handler -> this.handlers.put(handler.stepCode(), handler));
        this.workflowRunMapper = null;
        this.workflowStepRunMapper = null;
        this.objectMapper = auditObjectMapper();
        this.persistenceEnabled = false;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DailyWorkflowOrchestrator(List<DailyWorkflowStepHandler> handlers, WorkflowRunMapper workflowRunMapper, WorkflowStepRunMapper workflowStepRunMapper) {
        this.handlers = new EnumMap<>(WorkflowStepCode.class);
        handlers.forEach(handler -> this.handlers.put(handler.stepCode(), handler));
        this.workflowRunMapper = Objects.requireNonNull(workflowRunMapper, "workflowRunMapper");
        this.workflowStepRunMapper = Objects.requireNonNull(workflowStepRunMapper, "workflowStepRunMapper");
        this.objectMapper = auditObjectMapper();
        this.persistenceEnabled = true;
    }

    public DailyWorkflowRunResultVo runDailyWorkflow(DailyWorkflowTriggerDto request, WorkflowTriggerType triggerType) {
        DailyWorkflowTriggerDto safeRequest = request == null ? new DailyWorkflowTriggerDto() : request;
        WorkflowTriggerType safeTriggerType = triggerType == null ? WorkflowTriggerType.MANUAL : triggerType;
        LocalDateTime startedAt = LocalDateTime.now();
        String runId = UUID.randomUUID().toString();
        // Keep the API safe when callers omit the flag. The UI and scheduled
        // task explicitly send false when they intend to persist generated data.
        boolean dryRun = safeRequest.getDryRun() == null || safeRequest.getDryRun();
        LocalDate tradeDate = safeRequest.getTradeDate() == null ? LocalDate.now() : safeRequest.getTradeDate();
        List<String> symbols = normalizeSymbols(safeRequest.getSymbols());
        safeRequest.setTradeDate(tradeDate);
        safeRequest.setSymbols(symbols);
        DailyWorkflowContext context = new DailyWorkflowContext(runId, safeRequest, safeTriggerType);
        WorkflowRun persistedRun = persistStart(runId, safeRequest, safeTriggerType, dryRun, startedAt);

        List<DailyWorkflowStepResultVo> steps = new ArrayList<>();
        boolean blockedByFailure = false;
        for (WorkflowStepCode step : WorkflowStepCode.orderedSteps()) {
            DailyWorkflowStepResultVo stepResult = blockedByFailure
                    ? skipped(step, LocalDateTime.now(), "前置步骤失败，已跳过后续任务。")
                    : runStep(step, context, dryRun);
            steps.add(stepResult);
            persistStep(persistedRun, step, steps.size(), stepResult, safeRequest);
            blockedByFailure = blockedByFailure || "failed".equals(stepResult.getStatus());
        }

        // M1 may resolve an empty request to the current my-follow pool. Read
        // the request back after the handlers run so the API reports the
        // symbols that were actually processed instead of the original empty
        // input list.
        symbols = normalizeSymbols(safeRequest.getSymbols());
        safeRequest.setSymbols(symbols);
        DailyWorkflowRunResultVo result = new DailyWorkflowRunResultVo();
        result.setRunId(runId);
        result.setTriggerType(safeTriggerType.getCode());
        result.setTradeDate(safeRequest.getTradeDate());
        result.setSymbols(symbols);
        result.setDryRun(dryRun);
        result.setStatus(dryRun ? WorkflowRunStatus.SKIPPED.getCode() : resolveStatus(steps));
        result.setRiskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        result.setStartedAt(startedAt);
        result.setFinishedAt(LocalDateTime.now());
        result.setSteps(steps);
        result.setIntegrationDependencies(integrationDependencies());
        persistFinish(persistedRun, result);
        return result;
    }

    private WorkflowRun persistStart(String runId, DailyWorkflowTriggerDto request, WorkflowTriggerType triggerType,
                                     boolean dryRun, LocalDateTime startedAt) {
        WorkflowRun run = WorkflowRun.builder().bizDate(request.getTradeDate()).status(WorkflowRunStatus.RUNNING.getCode())
                .triggerType(triggerType.getCode()).triggerBy("daily-workflow:" + runId)
                .dryRun(dryRun).requestParams(toJson(request)).startedAt(startedAt).build();
        if (!persistenceEnabled) {
            return run;
        }
        workflowRunMapper.insert(run);
        return run;
    }

    private void persistStep(WorkflowRun run, WorkflowStepCode step, int order,
                             DailyWorkflowStepResultVo result, DailyWorkflowTriggerDto request) {
        if (!persistenceEnabled) return;
        if (run.getId() == null) {
            throw new IllegalStateException("workflow_run 未返回数据库主键，无法持久化步骤记录");
        }
        long duration = result.getStartedAt() == null || result.getFinishedAt() == null ? 0 :
                java.time.Duration.between(result.getStartedAt(), result.getFinishedAt()).toMillis();
        workflowStepRunMapper.insert(WorkflowStepRun.builder().workflowRunId(run.getId()).stepCode(step.getCode())
                .stepOrder(order).status(result.getStatus()).inputParams(toJson(request))
                .outputSummary(toJson(result.getDetails()))
                .durationMs(duration).startedAt(result.getStartedAt()).finishedAt(result.getFinishedAt())
                .errorMessage("failed".equals(result.getStatus()) ? result.getMessage() : null).build());
    }

    private void persistFinish(WorkflowRun run, DailyWorkflowRunResultVo result) {
        if (!persistenceEnabled) return;
        run.setStatus(result.getStatus());
        run.setFinishedAt(result.getFinishedAt());
        run.setErrorMessage(firstFailureMessage(result.getSteps()));
        run.setSummary(toJson(stepSummary(result)));
        workflowRunMapper.updateById(run);
    }

    private Map<String, Object> stepSummary(DailyWorkflowRunResultVo result) {
        long success = result.getSteps().stream().filter(step -> "success".equals(step.getStatus())).count();
        long failed = result.getSteps().stream().filter(step -> "failed".equals(step.getStatus())).count();
        long skipped = result.getSteps().stream().filter(step -> "skipped".equals(step.getStatus())).count();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", result.getSteps().size());
        summary.put("success", success);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("riskDisclaimer", result.getRiskDisclaimer());
        return summary;
    }

    private String firstFailureMessage(List<DailyWorkflowStepResultVo> steps) {
        return steps.stream()
                .filter(step -> "failed".equals(step.getStatus()))
                .map(DailyWorkflowStepResultVo::getMessage)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String toJson(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception e) { return "{}"; }
    }

    private ObjectMapper auditObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public List<DailyWorkflowDependencyVo> integrationDependencies() {
        return List.of(
                new DailyWorkflowDependencyVo(
                        "M1",
                        "真实行情数据同步",
                        "提供股票列表、交易日历、日 K 行情同步，并避免默认执行危险外部调用",
                        "数据源配置、限流、交易日历和幂等导入策略需要与调度参数对齐"),
                new DailyWorkflowDependencyVo(
                        "M2",
                        "技术因子计算",
                        "按交易日和股票代码计算并持久化因子快照",
                        "因子 JSON 结构、空行情处理和未来数据防泄露策略需要统一"),
                new DailyWorkflowDependencyVo(
                        "M3",
                        "Drools 规则推理",
                        "读取因子快照与启用 Drools 规则，输出规则命中结果",
                        "规则状态、优先级、冲突处理和异常隔离策略需要统一"),
                new DailyWorkflowDependencyVo(
                        "M4",
                        "信号输出与规则触发记录",
                        "生成看涨、看跌、观望等辅助决策方向，风险由独立影子闸门附加",
                        "必须保留历史 high_risk 审计兼容，风险不得覆盖新信号方向"),
                new DailyWorkflowDependencyVo(
                        "M5",
                        "预测结果验证与回测",
                        "验证历史信号表现，并支持候选规则回测",
                        "回测对象类型、时间切片和样本外验证口径需要与契约一致"),
                new DailyWorkflowDependencyVo(
                        "M6",
                        "AI 复盘分析与候选规则生成",
                        "基于真实信号和验证结果生成结构化复盘及候选规则",
                        "AI 输出必须可追溯，候选规则不能绕过回测与人工审核")
        );
    }

    private DailyWorkflowStepResultVo runStep(WorkflowStepCode step, DailyWorkflowContext context, boolean dryRun) {
        LocalDateTime startedAt = LocalDateTime.now();
        DailyWorkflowStepHandler handler = handlers.get(step);
        if (dryRun) {
            return skipped(step, startedAt, "dryRun=true，未执行真实任务，仅返回调度契约。");
        }
        if (handler == null) {
            return skipped(step, startedAt, "依赖模块尚未合并，当前仅保留调度占位。");
        }

        try {
            DailyWorkflowStepResultVo result = handler.execute(context);
            if (result == null) {
                return failed(step, startedAt, "步骤处理器返回空结果。");
            }
            return result;
        } catch (RuntimeException ex) {
            return failed(step, startedAt, ex.getMessage());
        }
    }

    private DailyWorkflowStepResultVo skipped(WorkflowStepCode step, LocalDateTime startedAt, String message) {
        DailyWorkflowStepResultVo result = new DailyWorkflowStepResultVo();
        result.setStepCode(step.getCode());
        result.setStepName(step.getName());
        result.setStatus("skipped");
        result.setMessage(message);
        result.setStartedAt(startedAt);
        result.setFinishedAt(LocalDateTime.now());
        result.setDetails(Map.of("dependency", step.getDependency()));
        return result;
    }

    private DailyWorkflowStepResultVo failed(WorkflowStepCode step, LocalDateTime startedAt, String message) {
        DailyWorkflowStepResultVo result = new DailyWorkflowStepResultVo();
        result.setStepCode(step.getCode());
        result.setStepName(step.getName());
        result.setStatus("failed");
        result.setMessage(message);
        result.setStartedAt(startedAt);
        result.setFinishedAt(LocalDateTime.now());
        return result;
    }

    private String resolveStatus(List<DailyWorkflowStepResultVo> steps) {
        if (steps.stream().anyMatch(step -> "failed".equals(step.getStatus()))) {
            return "failed";
        }
        if (steps.stream().anyMatch(step -> "skipped".equals(step.getStatus()) && !isOptionalSkip(step))) {
            return "partial";
        }
        return "success";
    }

    private boolean isOptionalSkip(DailyWorkflowStepResultVo step) {
        return step.getDetails() != null && Boolean.TRUE.equals(step.getDetails().get("optional"));
    }

    private List<String> normalizeSymbols(List<String> symbols) {
        if (symbols == null) {
            return List.of();
        }
        return symbols.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }
}
