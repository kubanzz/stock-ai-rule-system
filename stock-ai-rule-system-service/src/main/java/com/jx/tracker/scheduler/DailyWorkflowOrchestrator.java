package com.jx.tracker.scheduler;

import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.vo.DailyWorkflowDependencyVo;
import com.jx.tracker.domain.vo.DailyWorkflowRunResultVo;
import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DailyWorkflowOrchestrator {

    private final Map<WorkflowStepCode, DailyWorkflowStepHandler> handlers;

    public DailyWorkflowOrchestrator(List<DailyWorkflowStepHandler> handlers) {
        this.handlers = new EnumMap<>(WorkflowStepCode.class);
        handlers.forEach(handler -> this.handlers.put(handler.stepCode(), handler));
    }

    public DailyWorkflowRunResultVo runDailyWorkflow(DailyWorkflowTriggerDto request, WorkflowTriggerType triggerType) {
        DailyWorkflowTriggerDto safeRequest = request == null ? new DailyWorkflowTriggerDto() : request;
        LocalDateTime startedAt = LocalDateTime.now();
        String runId = UUID.randomUUID().toString();
        boolean dryRun = safeRequest.getDryRun() == null || safeRequest.getDryRun();
        LocalDate tradeDate = safeRequest.getTradeDate() == null ? LocalDate.now() : safeRequest.getTradeDate();
        List<String> symbols = normalizeSymbols(safeRequest.getSymbols());
        safeRequest.setTradeDate(tradeDate);
        safeRequest.setSymbols(symbols);
        DailyWorkflowContext context = new DailyWorkflowContext(runId, safeRequest, triggerType);

        List<DailyWorkflowStepResultVo> steps = WorkflowStepCode.orderedSteps().stream()
                .map(step -> runStep(step, context, dryRun))
                .toList();

        DailyWorkflowRunResultVo result = new DailyWorkflowRunResultVo();
        result.setRunId(runId);
        result.setTriggerType(triggerType.getCode());
        result.setTradeDate(tradeDate);
        result.setSymbols(symbols);
        result.setDryRun(dryRun);
        result.setStatus(resolveStatus(steps));
        result.setRiskDisclaimer(StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        result.setStartedAt(startedAt);
        result.setFinishedAt(LocalDateTime.now());
        result.setSteps(steps);
        result.setIntegrationDependencies(integrationDependencies());
        return result;
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
                        "JSON 规则推理",
                        "读取因子快照与启用 JSON 规则，输出规则命中结果",
                        "规则状态、优先级、冲突处理和异常隔离策略需要统一"),
                new DailyWorkflowDependencyVo(
                        "M4",
                        "信号输出与规则触发记录",
                        "生成看涨、看跌、观望、高风险等辅助决策信号",
                        "必须保留风险提示，避免写成确定性预测或收益保证"),
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
