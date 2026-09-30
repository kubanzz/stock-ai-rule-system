package com.jx.tracker.rule;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.rule.engine.JsonRuleEngineExecutor;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeRuleEngineExecutorTest {

    @Test
    void productionExecutionUsesDroolsOnlyWhenBothFormatExecutorsAreAvailable() throws Exception {
        RuleEngineExecutor jsonExecutor = request -> new RuleExecutionResult(
                new BigDecimal("999"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of("R_JSON_TREND_001"),
                List.of("JSON 规则识别趋势偏强")
        );
        RuleDefinition droolsRule = rule("R_DROOLS_TREND_001", RuleFormat.DROOLS);
        droolsRule.setRuleContent("""
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_DROOLS_TREND_001"
                when
                    $f : StockFactorFact(shortTermTrend == "strong_up")
                then
                    $f.addBullishScore(new BigDecimal("35"));
                    $f.addRiskScore(new BigDecimal("20"));
                    $f.addTriggeredRule("R_DROOLS_TREND_001");
                    $f.addExplanation("Drools 规则补充风险提示");
                end
                """);

        RuleExecutionResult result = compositeExecutor(jsonExecutor, new DroolsRuleEngineExecutor()).execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of("short_term_trend", "strong_up"),
                List.of(rule("R_JSON_TREND_001", RuleFormat.JSON), droolsRule)
        ));

        assertThat(result.bullishScore()).isEqualByComparingTo(new BigDecimal("35"));
        assertThat(result.bearishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.riskScore()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(result.triggeredRules()).containsExactly("R_DROOLS_TREND_001");
        assertThat(result.explanations()).containsExactly("Drools 规则补充风险提示");
    }

    @Test
    void productionDroolsKeepsUnavailableNumericFactorsFromTriggeringRules() throws Exception {
        RuleDefinition jsonRule = rule("R_JSON_GUARD", RuleFormat.JSON);
        jsonRule.setRuleContent("""
                {"conditions":[{"field":"rsi14","operator":"lt","value":30}],
                 "actions":{"risk_score":80}}
                """);
        RuleDefinition droolsRule = rule("R_DROOLS_GUARD", RuleFormat.DROOLS);
        droolsRule.setRuleContent("""
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_DROOLS_GUARD"
                when
                    $f : StockFactorFact(rsi < new BigDecimal("30"))
                then
                    $f.addRiskScore(new BigDecimal("80"));
                    $f.addTriggeredRule("R_DROOLS_GUARD");
                end
                """);
        Map<String, Object> factors = new HashMap<>();
        factors.put("rsi14", null);
        factors.put("rsi", "unknown");

        RuleExecutionResult result = compositeExecutor(new JsonRuleEngineExecutor(),
                new DroolsRuleEngineExecutor())
                .execute(new RuleExecutionRequest("AAPL", LocalDate.of(2026, 6, 20),
                        factors, List.of(jsonRule, droolsRule)));

        assertThat(result.triggeredRules()).isEmpty();
        assertThat(result.riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.ruleEvaluations()).hasSize(1);
        assertThat(result.ruleEvaluations()).extracting(evaluation -> evaluation.status())
                .containsExactly("NOT_MATCHED");
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
