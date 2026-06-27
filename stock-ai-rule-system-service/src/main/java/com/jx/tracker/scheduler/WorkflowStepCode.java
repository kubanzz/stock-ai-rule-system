package com.jx.tracker.scheduler;

import java.util.List;

public enum WorkflowStepCode {

    MARKET_DATA_COLLECTION("market_data_collection", "行情采集", "M1 行情与新闻数据采集"),
    FACTOR_CALCULATION("factor_calculation", "因子计算", "M2 技术因子计算"),
    RULE_INFERENCE("rule_inference", "规则推理", "M3 Drools 规则推理"),
    SIGNAL_GENERATION("signal_generation", "信号生成", "M4 信号输出与触发记录"),
    HISTORICAL_VERIFICATION("historical_verification", "历史验证", "M5 预测结果验证"),
    AI_REVIEW("ai_review", "AI 复盘", "M6 AI 复盘分析"),
    CANDIDATE_RULE_BACKTEST("candidate_rule_backtest", "候选规则回测", "M5/M6 回测与候选规则模块");

    private final String code;

    private final String name;

    private final String dependency;

    WorkflowStepCode(String code, String name, String dependency) {
        this.code = code;
        this.name = name;
        this.dependency = dependency;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDependency() {
        return dependency;
    }

    public static List<WorkflowStepCode> orderedSteps() {
        return List.of(
                MARKET_DATA_COLLECTION,
                FACTOR_CALCULATION,
                RULE_INFERENCE,
                SIGNAL_GENERATION,
                HISTORICAL_VERIFICATION,
                AI_REVIEW,
                CANDIDATE_RULE_BACKTEST
        );
    }
}
