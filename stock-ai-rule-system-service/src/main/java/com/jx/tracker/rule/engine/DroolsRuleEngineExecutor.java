package com.jx.tracker.rule.engine;

import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import org.kie.api.KieBase;
import org.kie.api.builder.Message;
import org.kie.api.builder.Results;
import org.kie.api.io.ResourceType;
import org.kie.api.runtime.KieSession;
import org.kie.internal.utils.KieHelper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

@Component
@Qualifier(CompositeRuleEngineExecutor.FORMAT_EXECUTOR_QUALIFIER)
@Order(200)
public class DroolsRuleEngineExecutor implements RuleEngineExecutor {

    @Override
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        StockFactorFact fact = StockFactorFact.from(request);
        List<RuleDefinition> rules = request.rules() == null ? List.of() : request.rules();

        for (RuleDefinition rule : executableRules(rules)) {
            executeRule(rule, fact);
        }

        return new RuleExecutionResult(
                normalizeNonNegative(fact.getBullishScore()),
                normalizeNonNegative(fact.getBearishScore()),
                normalizeNonNegative(fact.getRiskScore()),
                fact.getTriggeredRules(),
                fact.getExplanations()
        );
    }

    private void executeRule(RuleDefinition rule, StockFactorFact fact) {
        if (!StringUtils.hasText(rule.getRuleContent())) {
            throw new IllegalArgumentException("Empty Drools rule content: " + rule.getRuleCode());
        }

        KieHelper kieHelper = new KieHelper();
        kieHelper.addContent(rule.getRuleContent(), ResourceType.DRL);
        Results results = kieHelper.verify();
        if (results.hasMessages(Message.Level.ERROR)) {
            throw new IllegalArgumentException("Invalid Drools rule content: "
                    + rule.getRuleCode() + " " + results.getMessages(Message.Level.ERROR));
        }

        KieBase kieBase = kieHelper.build();
        KieSession kieSession = kieBase.newKieSession();
        try {
            kieSession.insert(fact);
            kieSession.fireAllRules();
        } finally {
            kieSession.dispose();
        }
    }

    private List<RuleDefinition> executableRules(List<RuleDefinition> rules) {
        return rules.stream()
                .filter(rule -> RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus()))
                .filter(rule -> RuleFormat.DROOLS.getCode().equals(rule.getRuleFormat()))
                .sorted(Comparator.comparing((RuleDefinition rule) -> rule.getPriority() == null ? 0 : rule.getPriority()).reversed())
                .toList();
    }

    private BigDecimal normalizeNonNegative(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).max(BigDecimal.ZERO).stripTrailingZeros();
    }
}
