package com.jx.tracker.rule;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DroolsRuleEngineExecutorTest {

    @Test
    void triggersMatchingDroolsRuleAndAppliesFactActions() throws Exception {
        RuleDefinition droolsRule = droolsRule("R_DROOLS_TREND_BREAKOUT_001", 100, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_DROOLS_TREND_BREAKOUT_001"
                salience 100
                when
                    $f : StockFactorFact(
                        shortTermTrend == "strong_up",
                        volumeStatus == "abnormal_high",
                        marketStatus != "weak"
                    )
                then
                    $f.addBullishScore(new BigDecimal("35"));
                    $f.addTriggeredRule("R_DROOLS_TREND_BREAKOUT_001");
                    $f.addExplanation("Drools 识别短期趋势强且成交量放大，仅作为辅助决策信号");
                end
                """);

        RuleExecutionResult result = droolsExecutor().execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of(
                        "short_term_trend", "strong_up",
                        "volume_status", "abnormal_high",
                        "market_status", "strong"
                ),
                List.of(droolsRule)
        ));

        assertThat(result.bullishScore()).isEqualByComparingTo(new BigDecimal("35"));
        assertThat(result.bearishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.triggeredRules()).containsExactly("R_DROOLS_TREND_BREAKOUT_001");
        assertThat(result.explanations()).containsExactly("Drools 识别短期趋势强且成交量放大，仅作为辅助决策信号");
    }

    @Test
    void ignoresDisabledJsonAndUnmatchedDroolsRules() throws Exception {
        RuleDefinition disabledDroolsRule = droolsRule("R_DISABLED_DROOLS", 100, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_DISABLED_DROOLS"
                when
                    $f : StockFactorFact()
                then
                    $f.addRiskScore(new BigDecimal("90"));
                    $f.addTriggeredRule("R_DISABLED_DROOLS");
                end
                """);
        disabledDroolsRule.setStatus(RuleLifecycleStatus.DISABLED.getCode());

        RuleDefinition jsonRule = droolsRule("R_JSON", 90, "json content");
        jsonRule.setRuleFormat(RuleFormat.JSON.getCode());

        RuleDefinition unmatchedDroolsRule = droolsRule("R_UNMATCHED_DROOLS", 80, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_UNMATCHED_DROOLS"
                when
                    $f : StockFactorFact(rsi > new BigDecimal("80"))
                then
                    $f.addRiskScore(new BigDecimal("45"));
                    $f.addTriggeredRule("R_UNMATCHED_DROOLS");
                end
                """);

        RuleExecutionResult result = droolsExecutor().execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of("rsi", 65),
                List.of(disabledDroolsRule, jsonRule, unmatchedDroolsRule)
        ));

        assertThat(result.triggeredRules()).isEmpty();
        assertThat(result.bullishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.bearishScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private RuleEngineExecutor droolsExecutor() throws Exception {
        return (RuleEngineExecutor) Class.forName("com.jx.tracker.rule.engine.DroolsRuleEngineExecutor")
                .getDeclaredConstructor()
                .newInstance();
    }

    private RuleDefinition droolsRule(String code, int priority, String content) {
        return RuleDefinition.builder()
                .ruleCode(code)
                .ruleName(code)
                .ruleType("trend")
                .ruleFormat(RuleFormat.DROOLS.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(priority)
                .ruleContent(content)
                .build();
    }
}
