package com.jx.tracker.rule;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import com.jx.tracker.rule.engine.RuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        assertThat(result.ruleEvaluations()).hasSize(1);
        assertThat(result.ruleEvaluations().getFirst().status()).isEqualTo("MATCHED");
        assertThat(result.ruleEvaluations().getFirst().evidenceStatus()).isEqualTo("PARTIAL");
        assertThat(result.ruleEvaluations().getFirst().conditions()).singleElement()
                .satisfies(condition -> {
                    assertThat(condition.field()).isEqualTo("$drools_rule");
                    assertThat(condition.actual()).isEqualTo("R_DROOLS_TREND_BREAKOUT_001");
                    assertThat(condition.status()).isEqualTo("MATCHED");
                });
        assertThat(result.ruleEvaluations().getFirst().bullishDelta()).isEqualByComparingTo("35");
    }

    @Test
    void executesActiveDroolsRulesInOneSessionUsingPersistedPriorityAsSalience() throws Exception {
        RuleDefinition lowPriorityRule = droolsRule("R_LOW_PRIORITY", 10, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_LOW_PRIORITY"
                salience 999
                when
                    $f : StockFactorFact(shortTermTrend == "up")
                then
                    $f.addBullishScore(new BigDecimal("5"));
                    $f.addTriggeredRule("R_LOW_PRIORITY");
                    $f.addExplanation("low priority");
                end
                """);
        RuleDefinition highPriorityRule = droolsRule("R_HIGH_PRIORITY", 100, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_HIGH_PRIORITY"
                salience -999
                when
                    $f : StockFactorFact(shortTermTrend == "up")
                then
                    $f.addBullishScore(new BigDecimal("7"));
                    $f.addTriggeredRule("R_HIGH_PRIORITY");
                    $f.addExplanation("high priority");
                end
                """);

        RuleExecutionResult result = droolsExecutor().execute(new RuleExecutionRequest(
                "AAPL", LocalDate.of(2026, 6, 20), Map.of("short_term_trend", "up"),
                List.of(lowPriorityRule, highPriorityRule)));

        assertThat(result.bullishScore()).isEqualByComparingTo("12");
        assertThat(result.triggeredRules()).containsExactly("R_HIGH_PRIORITY", "R_LOW_PRIORITY");
        assertThat(result.explanations()).containsExactly("high priority", "low priority");
        assertThat(result.ruleEvaluations()).extracting(evaluation -> evaluation.code())
                .containsExactly("R_HIGH_PRIORITY", "R_LOW_PRIORITY");
        assertThat(result.ruleEvaluations()).allSatisfy(evaluation -> {
            assertThat(evaluation.status()).isEqualTo("MATCHED");
            assertThat(evaluation.evidenceStatus()).isEqualTo("PARTIAL");
        });
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
        assertThat(result.ruleEvaluations()).hasSize(1);
        assertThat(result.ruleEvaluations().getFirst().status()).isEqualTo("NOT_MATCHED");
    }

    @Test
    void readsPersistedTechnicalFactorAliasesUsedByTheCalculator() throws Exception {
        RuleDefinition droolsRule = droolsRule("R_DROOLS_ALIAS_001", 100, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_DROOLS_ALIAS_001"
                when
                    $f : StockFactorFact(
                        rsi >= new BigDecimal("85"),
                        macd < BigDecimal.ZERO,
                        volumeRatio >= new BigDecimal("1.30")
                    )
                then
                    $f.addRiskScore(new BigDecimal("20"));
                    $f.addTriggeredRule("R_DROOLS_ALIAS_001");
                end
                """);

        RuleExecutionResult result = droolsExecutor().execute(new RuleExecutionRequest(
                "AAPL",
                LocalDate.of(2026, 6, 20),
                Map.of(
                        "rsi14", new BigDecimal("90"),
                        "macd_histogram", new BigDecimal("-0.20"),
                        "volume_ratio_5d", new BigDecimal("1.50")
                ),
                List.of(droolsRule)
        ));

        assertThat(result.riskScore()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(result.triggeredRules()).containsExactly("R_DROOLS_ALIAS_001");
    }

    @Test
    void missingAndUnavailableNumericAliasesDoNotAbortDroolsEvaluation() throws Exception {
        RuleDefinition rule = droolsRule("R_INCOMPLETE", 100, """
                import java.math.BigDecimal;
                import com.jx.tracker.rule.engine.StockFactorFact;

                rule "R_INCOMPLETE"
                when
                    $f : StockFactorFact(rsi < new BigDecimal("30"))
                then
                    $f.addRiskScore(new BigDecimal("80"));
                    $f.addTriggeredRule("R_INCOMPLETE");
                end
                """);
        Map<String, Object> factors = new HashMap<>();
        factors.put("rsi14", null);
        factors.put("rsi", "unknown");

        RuleExecutionResult result = droolsExecutor().execute(new RuleExecutionRequest(
                "AAPL", LocalDate.of(2026, 6, 20), factors, List.of(rule)));

        assertThat(result.triggeredRules()).isEmpty();
        assertThat(result.riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.ruleEvaluations().getFirst().status()).isEqualTo("NOT_MATCHED");
    }

    @Test
    void seededBasicDroolsRulesCompileAndMatchTheirDocumentedFactors() throws Exception {
        Map<String, String> seededRules = seededDroolsRules();
        assertThat(seededRules).hasSize(5);

        Map<String, Map<String, Object>> matchingFactors = Map.of(
                "R_DROOLS_TREND_VOLUME_CONFIRM_001", Map.of(
                        "short_term_trend", "strong_up", "volume_status", "abnormal_high"),
                "R_DROOLS_OVERHEAT_RISK_001", Map.of(
                        "rsi14", new BigDecimal("90"), "volume_status", "abnormal_high"),
                "R_DROOLS_BEARISH_GUARD_001", Map.of(
                        "short_term_trend", "down", "technical_status", "bearish"),
                "R_DROOLS_MACD_VOLUME_DIVERGENCE_001", Map.of(
                        "volume_ratio_5d", new BigDecimal("1.50"),
                        "macd_histogram", new BigDecimal("-0.20")),
                "R_DROOLS_OVERSOLD_NOTICE_001", Map.of(
                        "rsi14", new BigDecimal("25"), "technical_status", "oversold")
        );

        for (Map.Entry<String, String> entry : seededRules.entrySet()) {
            String code = entry.getKey();
            RuleExecutionResult result = droolsExecutor().execute(new RuleExecutionRequest(
                    "AAPL", LocalDate.of(2026, 6, 20), matchingFactors.get(code),
                    List.of(droolsRule(code, 100, entry.getValue()))
            ));
            assertThat(result.triggeredRules()).as(code).containsExactly(code);
            assertThat(result.explanations()).as(code).hasSize(1);
            assertThat(result.explanations().getFirst()).contains("辅助决策");
        }

        List<RuleDefinition> allSeededRules = seededRules.entrySet().stream()
                .map(entry -> droolsRule(entry.getKey(), 100, entry.getValue()))
                .toList();
        RuleExecutionResult incompleteDataResult = droolsExecutor().execute(new RuleExecutionRequest(
                "AAPL", LocalDate.of(2026, 6, 20),
                Map.of("data_status", "insufficient_data"), allSeededRules
        ));
        assertThat(incompleteDataResult.triggeredRules()).isEmpty();
        assertThat(incompleteDataResult.riskScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private Map<String, String> seededDroolsRules() throws Exception {
        String resource = "/db/migration/V6__seed_basic_drools_rules.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Pattern ruleRow = Pattern.compile(
                    "'(?<code>R_DROOLS_[A-Z0-9_]+)'\\s*,\\s*'[^']+'\\s*,\\s*'[^']+'\\s*,\\s*'[^']+'\\s*,\\s*'(?<drl>(?:[^'\\\\]|\\\\.)*)'\\s*,\\s*'drools'"
            );
            Matcher matcher = ruleRow.matcher(sql);
            Map<String, String> rules = new LinkedHashMap<>();
            while (matcher.find()) {
                rules.put(matcher.group("code"), matcher.group("drl").replace("\\n", "\n"));
            }
            return rules;
        }
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
