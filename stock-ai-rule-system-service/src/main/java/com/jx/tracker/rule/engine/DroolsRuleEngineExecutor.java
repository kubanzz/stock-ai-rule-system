package com.jx.tracker.rule.engine;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.event.rule.AfterMatchFiredEvent;
import org.kie.api.event.rule.BeforeMatchFiredEvent;
import org.kie.api.event.rule.DefaultAgendaEventListener;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Qualifier(CompositeRuleEngineExecutor.FORMAT_EXECUTOR_QUALIFIER)
@Order(200)
public class DroolsRuleEngineExecutor implements RuleEngineExecutor {

    private static final Pattern RULE_DECLARATION = Pattern.compile(
            "^\\s*rule\\s+(?:\"([^\"]+)\"|'([^']+)'|([^\\s{]+))(?:\\s+when)?\\s*$");
    private static final Pattern SALIENCE_DECLARATION = Pattern.compile(
            "^\\s*salience\\s+[-+]?\\d+\\s*$");

    @Override
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        StockFactorFact fact = StockFactorFact.from(request);
        List<RuleDefinition> rules = request.rules() == null ? List.of() : request.rules();
        List<RuleDefinition> executableRules = executableRules(rules);
        Map<String, RuleEvaluationAccumulator> evaluationsByCode = new LinkedHashMap<>();
        if (!executableRules.isEmpty()) {
            executeRules(executableRules, fact, evaluationsByCode);
        }

        List<RuleEvaluation> ruleEvaluations = executableRules.stream()
                .map(rule -> evaluationsByCode.get(rule.getRuleCode()).toRuleEvaluation())
                .toList();

        return new RuleExecutionResult(
                normalizeNonNegative(fact.getBullishScore()),
                normalizeNonNegative(fact.getBearishScore()),
                normalizeNonNegative(fact.getRiskScore()),
                fact.getTriggeredRules(),
                fact.getExplanations(),
                List.copyOf(ruleEvaluations)
        );
    }

    /**
     * Validates a production DRL before it can be saved or enabled.  This uses
     * the same salience normalization and KIE verification path as execution,
     * so malformed rules fail at the rule-management boundary instead of
     * poisoning a later signal-generation batch.
     */
    public void validateRuleContent(String ruleCode, String content, Integer priority) {
        RuleDefinition rule = RuleDefinition.builder()
                .ruleCode(ruleCode)
                .ruleContent(content)
                .ruleFormat(RuleFormat.DROOLS.getCode())
                .status(RuleLifecycleStatus.ACTIVE.getCode())
                .priority(priority)
                .build();
        String normalizedContent = normalizeSalience(rule);
        KieHelper kieHelper = new KieHelper();
        kieHelper.addContent(normalizedContent, ResourceType.DRL);
        Results results = kieHelper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalArgumentException("Invalid Drools rule content for " + ruleCode + " "
                    + results.getMessages(Message.Level.ERROR));
        }
        kieHelper.build();
    }

    private void executeRules(List<RuleDefinition> rules, StockFactorFact fact,
                              Map<String, RuleEvaluationAccumulator> evaluationsByCode) {
        KieHelper kieHelper = new KieHelper();
        Map<String, RuleDefinition> definitionsByDroolsName = new LinkedHashMap<>();

        for (RuleDefinition rule : rules) {
            if (!StringUtils.hasText(rule.getRuleContent())) {
                throw new IllegalArgumentException("Empty Drools rule content: " + rule.getRuleCode());
            }
            RuleEvaluationAccumulator previousEvaluation = evaluationsByCode.putIfAbsent(
                    rule.getRuleCode(), new RuleEvaluationAccumulator(rule));
            if (previousEvaluation != null) {
                throw new IllegalArgumentException("Duplicate Drools rule code: " + rule.getRuleCode());
            }
            String normalizedContent = normalizeSalience(rule);
            for (String droolsName : declaredRuleNames(normalizedContent, rule.getRuleCode())) {
                RuleDefinition previous = definitionsByDroolsName.putIfAbsent(droolsName, rule);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate Drools rule name '" + droolsName
                            + "' in " + previous.getRuleCode() + " and " + rule.getRuleCode());
                }
            }
            kieHelper.addContent(normalizedContent, ResourceType.DRL);
        }

        Results results = kieHelper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            String ruleCodes = rules.stream().map(RuleDefinition::getRuleCode).toList().toString();
            throw new IllegalArgumentException("Invalid Drools rule content for "
                    + ruleCodes + " " + results.getMessages(Message.Level.ERROR));
        }

        KieBase kieBase = kieHelper.build();
        KieSession kieSession = kieBase.newKieSession();
        try {
            kieSession.addEventListener(new RuleEvaluationListener(
                    fact, definitionsByDroolsName, evaluationsByCode));
            kieSession.insert(fact);
            kieSession.fireAllRules();
        } finally {
            kieSession.dispose();
        }
    }

    private List<RuleDefinition> executableRules(List<RuleDefinition> rules) {
        return rules.stream()
                .filter(rule -> rule != null)
                .filter(rule -> RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus()))
                .filter(rule -> RuleFormat.DROOLS.getCode().equals(rule.getRuleFormat()))
                .filter(rule -> !Boolean.FALSE.equals(rule.getEnabled()))
                .sorted(Comparator.comparing((RuleDefinition rule) -> rule.getPriority() == null ? 0 : rule.getPriority()).reversed())
                .toList();
    }

    private String normalizeSalience(RuleDefinition rule) {
        int priority = rule.getPriority() == null ? 0 : rule.getPriority();
        String[] lines = rule.getRuleContent().replace("\r\n", "\n").split("\n", -1);
        StringBuilder normalized = new StringBuilder(rule.getRuleContent().length() + 32);
        boolean ruleSeen = false;
        for (String line : lines) {
            Matcher ruleMatcher = RULE_DECLARATION.matcher(line);
            if (ruleMatcher.matches()) {
                normalized.append(line).append('\n');
                normalized.append("salience ").append(priority).append('\n');
                ruleSeen = true;
                continue;
            }
            if (ruleSeen && SALIENCE_DECLARATION.matcher(line).matches()) {
                continue;
            }
            normalized.append(line).append('\n');
        }
        if (!ruleSeen) {
            throw new IllegalArgumentException("No Drools rule declaration: " + rule.getRuleCode());
        }
        return normalized.toString();
    }

    private List<String> declaredRuleNames(String content, String ruleCode) {
        List<String> names = new ArrayList<>();
        for (String line : content.split("\\R")) {
            Matcher matcher = RULE_DECLARATION.matcher(line);
            if (matcher.matches()) {
                String name = matcher.group(1) != null ? matcher.group(1)
                        : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalArgumentException("No Drools rule declaration: " + ruleCode);
        }
        return names;
    }

    private static final class RuleEvaluationAccumulator {
        private final RuleDefinition rule;
        private boolean matched;
        private BigDecimal bullishDelta = BigDecimal.ZERO;
        private BigDecimal bearishDelta = BigDecimal.ZERO;
        private BigDecimal riskDelta = BigDecimal.ZERO;
        private final List<String> explanations = new ArrayList<>();

        private RuleEvaluationAccumulator(RuleDefinition rule) {
            this.rule = rule;
        }

        private void record(StockFactorFact fact, ScoreSnapshot before) {
            matched = true;
            bullishDelta = bullishDelta.add(fact.getBullishScore().subtract(before.bullish()));
            bearishDelta = bearishDelta.add(fact.getBearishScore().subtract(before.bearish()));
            riskDelta = riskDelta.add(fact.getRiskScore().subtract(before.risk()));
            explanations.addAll(fact.getExplanations().subList(
                    before.explanationCount(), fact.getExplanations().size()));
        }

        private RuleEvaluation toRuleEvaluation() {
            String explanation = explanations.isEmpty() ? null : String.join("；", explanations);
            List<RuleConditionEvaluation> conditions = List.of(new RuleConditionEvaluation(
                    "$drools_rule", "fired", matched ? rule.getRuleCode() : null, rule.getRuleCode(),
                    matched ? "MATCHED" : "NOT_MATCHED"));
            return RuleEvaluation.of(rule, matched ? "MATCHED" : "NOT_MATCHED", conditions,
                    bullishDelta, bearishDelta, riskDelta, explanation, "PARTIAL");
        }
    }

    private record ScoreSnapshot(BigDecimal bullish, BigDecimal bearish,
                                 BigDecimal risk, int explanationCount) {
        private static ScoreSnapshot capture(StockFactorFact fact) {
            return new ScoreSnapshot(fact.getBullishScore(), fact.getBearishScore(),
                    fact.getRiskScore(), fact.getExplanations().size());
        }
    }

    private static final class RuleEvaluationListener extends DefaultAgendaEventListener {
        private final StockFactorFact fact;
        private final Map<String, RuleDefinition> definitionsByDroolsName;
        private final Map<String, RuleEvaluationAccumulator> evaluationsByCode;
        private final Map<String, ScoreSnapshot> snapshotsByRuleName = new HashMap<>();

        private RuleEvaluationListener(StockFactorFact fact,
                                       Map<String, RuleDefinition> definitionsByDroolsName,
                                       Map<String, RuleEvaluationAccumulator> evaluationsByCode) {
            this.fact = fact;
            this.definitionsByDroolsName = definitionsByDroolsName;
            this.evaluationsByCode = evaluationsByCode;
        }

        @Override
        public void beforeMatchFired(BeforeMatchFiredEvent event) {
            snapshotsByRuleName.put(event.getMatch().getRule().getName(), ScoreSnapshot.capture(fact));
        }

        @Override
        public void afterMatchFired(AfterMatchFiredEvent event) {
            String droolsName = event.getMatch().getRule().getName();
            RuleDefinition rule = definitionsByDroolsName.get(droolsName);
            if (rule == null) {
                return;
            }
            ScoreSnapshot before = snapshotsByRuleName.remove(droolsName);
            if (before != null) {
                evaluationsByCode.get(rule.getRuleCode()).record(fact, before);
            }
        }
    }

    private BigDecimal normalizeNonNegative(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).max(BigDecimal.ZERO).stripTrailingZeros();
    }
}
