package com.jx.tracker.rule;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeRuleEngineExecutorTest {

    @Test
    void mergesScoresTriggeredRulesAndExplanationsFromJsonAndDroolsExecutors() throws Exception {
        RuleEngineExecutor jsonExecutor = request -> new RuleExecutionResult(
                new BigDecimal("25"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of("R_JSON_TREND_001"),
                List.of("JSON 规则识别趋势偏强")
        );
        RuleEngineExecutor droolsExecutor = request -> new RuleExecutionResult(
                new BigDecimal("35"),
                BigDecimal.ZERO,
                new BigDecimal("20"),
                List.of("R_DROOLS_TREND_001"),
                List.of("Drools 规则补充风险提示")
        );

        RuleExecutionResult result = compositeExecutor(jsonExecutor, droolsExecutor).execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of("short_term_trend", "strong_up"),
                List.of(rule("R_JSON_TREND_001", RuleFormat.JSON), rule("R_DROOLS_TREND_001", RuleFormat.DROOLS))
        ));

        assertThat(result.bullishScore()).isEqualByComparingTo(new BigDecimal("60"));
        assertThat(result.bearishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.riskScore()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(result.triggeredRules()).containsExactly("R_JSON_TREND_001", "R_DROOLS_TREND_001");
        assertThat(result.explanations()).containsExactly("JSON 规则识别趋势偏强", "Drools 规则补充风险提示");
    }

    private RuleEngineExecutor compositeExecutor(RuleEngineExecutor... executors) throws Exception {
        Class<?> executorClass = Class.forName("com.jx.tracker.rule.engine.CompositeRuleEngineExecutor");
        Constructor<?> constructor = executorClass.getDeclaredConstructor(List.class);
        return (RuleEngineExecutor) constructor.newInstance(List.of(executors));
    }

    private RuleDefinition rule(String code, RuleFormat ruleFormat) {
        return RuleDefinition.builder()
                .ruleCode(code)
                .ruleName(code)
                .ruleType("trend")
                .ruleFormat(ruleFormat.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(100)
                .ruleContent("{}")
                .build();
    }
}
