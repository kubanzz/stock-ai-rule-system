package com.jx.tracker.rule;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRuleEngineExecutorTest {

    private final JsonRuleEngineExecutor executor = new JsonRuleEngineExecutor();

    @Test
    void triggersMatchingJsonRulesAndAppliesScoreActionsInPriorityOrder() {
        RuleDefinition bullishRule = rule("R_TREND_BREAKOUT_001", 100, """
                {
                  "conditions": [
                    {"field": "short_term_trend", "operator": "eq", "value": "strong_up"},
                    {"field": "volume_status", "operator": "eq", "value": "abnormal_high"},
                    {"field": "market_status", "operator": "ne", "value": "weak"}
                  ],
                  "actions": {
                    "bullish_score": 25,
                    "explanation": "短期趋势强，成交量放大，市场环境未明显走弱"
                  }
                }
                """);
        RuleDefinition riskRule = rule("R_RISK_OVERBOUGHT_001", 90, """
                {
                  "conditions": [
                    {"field": "rsi", "operator": "gt", "value": 75},
                    {"field": "price_change_5d", "operator": "gt", "value": 12}
                  ],
                  "actions": {
                    "risk_score": 30,
                    "bullish_score": -10,
                    "explanation": "RSI 过热且短期涨幅较大，存在回调风险"
                  }
                }
                """);

        RuleExecutionResult result = executor.execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of(
                        "short_term_trend", "strong_up",
                        "volume_status", "abnormal_high",
                        "market_status", "strong",
                        "rsi", 82,
                        "price_change_5d", 14.5
                ),
                List.of(riskRule, bullishRule)
        ));

        assertThat(result.bullishScore()).isEqualByComparingTo(new BigDecimal("15"));
        assertThat(result.bearishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.riskScore()).isEqualByComparingTo(new BigDecimal("30"));
        assertThat(result.triggeredRules()).containsExactly("R_TREND_BREAKOUT_001", "R_RISK_OVERBOUGHT_001");
        assertThat(result.explanations()).containsExactly(
                "短期趋势强，成交量放大，市场环境未明显走弱",
                "RSI 过热且短期涨幅较大，存在回调风险"
        );
    }

    @Test
    void ignoresDisabledNonJsonAndUnmatchedRules() {
        RuleDefinition disabledRule = rule("R_DISABLED", 100, """
                {
                  "conditions": [{"field": "market_status", "operator": "eq", "value": "weak"}],
                  "actions": {"risk_score": 50, "explanation": "disabled"}
                }
                """);
        disabledRule.setStatus(RuleLifecycleStatus.DISABLED.getCode());

        RuleDefinition droolsRule = rule("R_DROOLS", 90, "rule content");
        droolsRule.setRuleFormat(RuleFormat.DROOLS.getCode());

        RuleDefinition unmatchedRule = rule("R_UNMATCHED", 80, """
                {
                  "conditions": [{"field": "sentiment_status", "operator": "eq", "value": "negative"}],
                  "actions": {"bearish_score": 20, "explanation": "unmatched"}
                }
                """);

        RuleExecutionResult result = executor.execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of("market_status", "weak", "sentiment_status", "positive"),
                List.of(disabledRule, droolsRule, unmatchedRule)
        ));

        assertThat(result.triggeredRules()).isEmpty();
        assertThat(result.bullishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.bearishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private RuleDefinition rule(String code, int priority, String content) {
        return RuleDefinition.builder()
                .ruleCode(code)
                .ruleName(code)
                .ruleType("trend")
                .ruleFormat(RuleFormat.JSON.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(priority)
                .ruleContent(content)
                .build();
    }
}
