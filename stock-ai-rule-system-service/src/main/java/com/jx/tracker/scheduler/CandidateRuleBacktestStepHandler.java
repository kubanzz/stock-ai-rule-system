package com.jx.tracker.scheduler;

import com.jx.tracker.backtest.BacktestService;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.BacktestResult;
import com.jx.tracker.domain.enums.RuleObjectType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class CandidateRuleBacktestStepHandler implements DailyWorkflowStepHandler {

    private final BacktestService backtestService;

    public CandidateRuleBacktestStepHandler(BacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.CANDIDATE_RULE_BACKTEST;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        List<String> candidateCodes = candidateCodes(context);
        if (candidateCodes.isEmpty()) {
            return DailyWorkflowStepResults.skipped(
                    stepCode(),
                    startedAt,
                    "AI 复盘未产生候选规则，本轮无需自动回测。",
                    Map.of("optional", true, "backtestCount", 0)
            );
        }
        List<BacktestResult> results = new ArrayList<>();
        for (String candidateCode : candidateCodes) {
            results.add(backtestService.runCandidateRuleBacktest(backtestRequest(context, candidateCode)));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("backtestCount", results.size());
        details.put("candidateRuleCodes", candidateCodes);
        details.put("reportIds", results.stream().map(BacktestResult::getId).toList());
        details.put("statuses", results.stream().map(BacktestResult::getStatus).toList());
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "候选规则自动回测完成，仍需人工审核后才能发布。",
                details
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> candidateCodes(DailyWorkflowContext context) {
        List<?> values = context.getAttribute(AiReviewWorkflowStepHandler.CANDIDATE_RULE_CODES_ATTRIBUTE, List.class);
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(code -> !code.isBlank())
                .distinct()
                .toList();
    }

    private BacktestRequestDto backtestRequest(DailyWorkflowContext context, String candidateCode) {
        BacktestRequestDto request = new BacktestRequestDto();
        request.setObjectType(RuleObjectType.CANDIDATE_RULE.getCode());
        request.setObjectCode(candidateCode);
        request.setStartDate(context.getRequest().getTradeDate().minusDays(90));
        request.setEndDate(context.getRequest().getTradeDate());
        request.setHoldingPeriod(5);
        return request;
    }
}
