package com.jx.tracker.ai.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.dto.AiReviewRequestDto;
import com.jx.tracker.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AiReviewPromptTemplate {

    private final ObjectMapper objectMapper;

    public AiReviewPromptTemplate(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String render(AiReviewRequestDto request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", request.getDate() == null ? null : request.getDate().toString());
        payload.put("mode", request.getMode());
        payload.put("symbol", request.getSymbol());
        payload.put("signal_id", request.getSignalId());
        payload.put("prediction", request.getPrediction());
        payload.put("actual_result", request.getActualResult());
        payload.put("market_context", request.getMarketContext());
        payload.put("technical_indicators", request.getTechnicalIndicators());
        payload.put("historical_samples", request.getHistoricalSamples());

        try {
            return """
                    你是一个量化策略分析助手。请分析股票信号误判原因，并只输出结构化 JSON。

                    安全边界：
                    - 系统输出仅作为股票研究和辅助决策信号。
                    - 不得输出确定性预测、真实交易指令或收益保证。
                    - AI 只能生成候选规则，不能修改 active 规则。
                    - 所有建议必须进入回测和人工审核。

                    输入数据：
                    %s

                    请严格输出以下 JSON 字段：
                    {
                      "diagnosis": "误判原因或复盘结论",
                      "related_rules": ["规则编码"],
                      "suggestions": [
                        {
                          "type": "add_filter|adjust_threshold|add_risk_guard|observe",
                          "rule_id": "目标规则编码",
                          "condition": "候选条件表达式",
                          "original_content": "原规则片段，可为空",
                          "proposed_content": "候选规则内容，可为空",
                          "reason": "生成候选规则的原因",
                          "risk": "潜在风险",
                          "need_backtest": true
                        }
                      ],
                      "need_backtest": true,
                      "risk": "%s"
                    }
                    """.formatted(objectMapper.writeValueAsString(payload), StockRiskConstants.SIGNAL_RISK_DISCLAIMER);
        } catch (JsonProcessingException e) {
            throw new ServiceException("构建 AI 复盘 Prompt 失败", e);
        }
    }
}
