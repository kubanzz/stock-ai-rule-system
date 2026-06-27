package com.jx.tracker.ai.review;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(LlmClient.class)
public class MockLlmClient implements LlmClient {

    @Override
    public LlmResponse complete(LlmRequest request) {
        return new LlmResponse("mock-llm", """
                {
                  "diagnosis": "样本信息不足，暂不建议生成候选规则。",
                  "related_rules": [],
                  "suggestions": [],
                  "need_backtest": false,
                  "risk": "本系统输出仅用于股票研究和辅助决策，不构成投资建议，不代表确定性预测，也不保证收益。"
                }
                """);
    }
}
