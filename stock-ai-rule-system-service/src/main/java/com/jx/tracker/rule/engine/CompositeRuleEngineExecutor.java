package com.jx.tracker.rule.engine;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Primary
@Component
public class CompositeRuleEngineExecutor implements RuleEngineExecutor {

    public static final String FORMAT_EXECUTOR_QUALIFIER = "formatRuleEngineExecutor";

    /**
     * The production signal path deliberately has one scoring source.  JSON
     * rules are still supported by {@link JsonRuleEngineExecutor} for candidate
     * rule backtests, but they must not be aggregated with production Drools
     * rules because the same business condition could otherwise score twice.
     */
    private final RuleEngineExecutor productionExecutor;

    @Autowired
    public CompositeRuleEngineExecutor(DroolsRuleEngineExecutor productionExecutor) {
        this.productionExecutor = Objects.requireNonNull(productionExecutor, "productionExecutor");
    }

    /**
     * Compatibility constructor for unit tests and callers that previously
     * supplied all format executors.  It intentionally selects Drools only;
     * passing a mixed JSON/Drools list can no longer cause both engines to run.
     */
    @Deprecated
    public CompositeRuleEngineExecutor(List<RuleEngineExecutor> executors) {
        this.productionExecutor = selectDroolsExecutor(executors);
    }

    @Override
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        return productionExecutor.execute(request);
    }

    private static RuleEngineExecutor selectDroolsExecutor(List<RuleEngineExecutor> executors) {
        if (executors == null || executors.isEmpty()) {
            throw new IllegalArgumentException("At least one Drools rule executor is required");
        }
        return executors.stream()
                .filter(DroolsRuleEngineExecutor.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "CompositeRuleEngineExecutor requires a DroolsRuleEngineExecutor"));
    }
}
