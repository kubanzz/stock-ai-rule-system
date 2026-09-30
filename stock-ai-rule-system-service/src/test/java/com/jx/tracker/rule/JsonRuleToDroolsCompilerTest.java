package com.jx.tracker.rule;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import com.jx.tracker.rule.engine.JsonRuleToDroolsCompiler;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonRuleToDroolsCompilerTest {

    private final JsonRuleToDroolsCompiler compiler = new JsonRuleToDroolsCompiler();

    @Test
    void compilesRestrictedCandidateJsonIntoExecutableDroolsAndPreservesEvidence() {
        String drl = compiler.compile("CR_CANDIDATE_001", """
                {
                  "conditions": [
                    {"field":"data_status","operator":"eq","value":"normal"},
                    {"field":"short_term_trend","operator":"in","value":["up","strong_up"]},
                    {"field":"macd_histogram","operator":"gt","value":0}
                  ],
                  "actions": {"bullish_score":25,"risk_score":5,"explanation":"候选规则命中"}
                }
                """);

        assertThat(drl)
                .contains("rule \"CR_CANDIDATE_001\"")
                .contains("eval($f.matches")
                .contains("addBullishScore")
                .contains("addRiskScore");

        RuleDefinition productionRule = RuleDefinition.builder()
                .ruleCode("CR_CANDIDATE_001")
                .ruleName("候选规则")
                .ruleContent(drl)
                .ruleFormat(RuleFormat.DROOLS.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(10)
                .build();
        RuleExecutionResult result = new DroolsRuleEngineExecutor().execute(new RuleExecutionRequest(
                "600000.SH", LocalDate.of(2026, 9, 29),
                Map.of("data_status", "normal", "short_term_trend", "strong_up",
                        "macd_histogram", new BigDecimal("0.12")),
                List.of(productionRule)));

        assertThat(result.bullishScore()).isEqualByComparingTo("25");
        assertThat(result.riskScore()).isEqualByComparingTo("5");
        assertThat(result.triggeredRules()).containsExactly("CR_CANDIDATE_001");
        assertThat(result.explanations()).containsExactly("候选规则命中");
        assertThat(result.ruleEvaluations()).singleElement().satisfies(evaluation -> {
            assertThat(evaluation.status()).isEqualTo("MATCHED");
            assertThat(evaluation.bullishDelta()).isEqualByComparingTo("25");
            assertThat(evaluation.riskDelta()).isEqualByComparingTo("5");
        });
    }

    @Test
    void rejectsMalformedOrUnsafeCandidateContent() {
        assertThatThrownBy(() -> compiler.compile("CR_BAD", "not-json"))
                .hasMessageContaining("合法 JSON");
        assertThatThrownBy(() -> compiler.compile("CR_BAD", """
                {"conditions":[{"field":"technical_status;java.lang.Runtime","operator":"eq","value":"bearish"}],"actions":{}}
                """))
                .hasMessageContaining("不支持的字符");
        assertThatThrownBy(() -> compiler.compile("CR_BAD", """
                {"conditions":[{"field":"technical_status","operator":"eq","value":"bearish"}],"actions":{"bullish_score":"oops"}}
                """))
                .hasMessageContaining("动作分数必须是数字");
        assertThatThrownBy(() -> compiler.compile("CR_BAD", """
                {"conditions":[],"actions":{"bullish_score":1}}
                """))
                .hasMessageContaining("conditions 数组");
        assertThatThrownBy(() -> compiler.compile("CR_BAD", """
                {"conditions":[{"field":"technical_status","operator":"eq","value":"bearish"}],"actions":{}}
                """))
                .hasMessageContaining("至少需要一个分值字段");
    }
}
