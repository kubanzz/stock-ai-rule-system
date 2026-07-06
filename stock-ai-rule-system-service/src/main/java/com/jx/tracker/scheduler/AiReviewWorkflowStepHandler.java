package com.jx.tracker.scheduler;

import com.jx.tracker.ai.review.AiReviewService;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.AiReviewRequestDto;
import com.jx.tracker.domain.dto.CandidateRuleDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class AiReviewWorkflowStepHandler implements DailyWorkflowStepHandler {

    public static final String CANDIDATE_RULE_CODES_ATTRIBUTE = "candidateRuleCodes";

    private final AiReviewService aiReviewService;

    public AiReviewWorkflowStepHandler(AiReviewService aiReviewService) {
        this.aiReviewService = aiReviewService;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.AI_REVIEW;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        List<String> targets = context.getRequest().getSymbols().isEmpty()
                ? List.of("")
                : context.getRequest().getSymbols();
        List<String> candidateCodes = new ArrayList<>();
        for (String target : targets) {
            var response = aiReviewService.review(reviewRequest(context, target));
            candidateCodes.addAll(response.getCandidateRules()
                    .stream()
                    .map(CandidateRuleDto::getCandidateCode)
                    .filter(code -> code != null && !code.isBlank())
                    .toList());
        }
        context.putAttribute(CANDIDATE_RULE_CODES_ATTRIBUTE, List.copyOf(candidateCodes));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reviewCount", targets.size());
        details.put("candidateRuleCount", candidateCodes.size());
        details.put("candidateRuleCodes", List.copyOf(candidateCodes));
        details.put("riskDisclaimer", StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "AI 复盘完成，仅生成报告和候选规则，未直接修改生产规则。",
                details
        );
    }

    private AiReviewRequestDto reviewRequest(DailyWorkflowContext context, String symbol) {
        AiReviewRequestDto request = new AiReviewRequestDto();
        request.setDate(context.getRequest().getTradeDate());
        request.setMode("daily");
        request.setSymbol(symbol == null || symbol.isBlank() ? null : symbol);
        request.setMarketContext("每日工作流自动复盘，信号仅作为辅助决策，不保证收益。");
        request.setTechnicalIndicators("已完成行情同步、因子计算、规则推理/信号生成和历史验证步骤。");
        request.setHistoricalSamples("候选规则必须完成回测和人工审核后才能发布。");
        return request;
    }
}
