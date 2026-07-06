package com.jx.tracker.ai.review;

import com.jx.tracker.constant.StockRiskConstants;

public class MockLlmClient implements LlmClient {

    @Override
    public LlmResponse complete(LlmRequest request) {
        return new LlmResponse("mock-llm", """
                {
                  "diagnosis": "样本信息不足，暂不建议生成候选规则。",
                  "related_rules": [],
                  "suggestions": [],
                  "need_backtest": false,
                  "risk": "%s"
                }
                """.formatted(StockRiskConstants.SIGNAL_RISK_DISCLAIMER));
    }
}
