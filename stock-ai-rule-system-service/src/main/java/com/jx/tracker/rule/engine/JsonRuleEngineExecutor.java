package com.jx.tracker.rule.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@Qualifier(CompositeRuleEngineExecutor.FORMAT_EXECUTOR_QUALIFIER)
@Order(100)
public class JsonRuleEngineExecutor implements RuleEngineExecutor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        BigDecimal bullishScore = BigDecimal.ZERO;
        BigDecimal bearishScore = BigDecimal.ZERO;
        BigDecimal riskScore = BigDecimal.ZERO;
        List<String> triggeredRules = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        List<RuleEvaluation> ruleEvaluations = new ArrayList<>();

        List<RuleDefinition> rules = request.rules() == null ? List.of() : request.rules();
        for (RuleDefinition rule : executableRules(rules)) {
            JsonNode root = parseRuleContent(rule);
            List<RuleConditionEvaluation> conditions = evaluateConditions(root.path("conditions"), request.factors(), rule);
            boolean matched = conditions.stream().allMatch(condition -> "MATCHED".equals(condition.status()));
            JsonNode actions = root.path("actions");
            String explanation = matched && StringUtils.hasText(actions.path("explanation").asText())
                    ? actions.path("explanation").asText() : null;
            if (!matched) {
                ruleEvaluations.add(RuleEvaluation.of(rule, "NOT_MATCHED", conditions,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, explanation, "FULL"));
                continue;
            }

            BigDecimal bullishDelta = decimalAction(actions, "bullish_score");
            BigDecimal bearishDelta = decimalAction(actions, "bearish_score");
            BigDecimal riskDelta = decimalAction(actions, "risk_score");
            bullishScore = bullishScore.add(bullishDelta);
            bearishScore = bearishScore.add(bearishDelta);
            riskScore = riskScore.add(riskDelta);
            triggeredRules.add(rule.getRuleCode());
            if (explanation != null) {
                explanations.add(explanation);
            }
            ruleEvaluations.add(RuleEvaluation.of(rule, "MATCHED", conditions,
                    bullishDelta, bearishDelta, riskDelta, explanation, "FULL"));
        }

        return new RuleExecutionResult(
                normalizeNonNegative(bullishScore),
                normalizeNonNegative(bearishScore),
                normalizeNonNegative(riskScore),
                List.copyOf(triggeredRules),
                List.copyOf(explanations),
                List.copyOf(ruleEvaluations)
        );
    }

    private List<RuleDefinition> executableRules(List<RuleDefinition> rules) {
        return rules.stream()
                .filter(rule -> RuleLifecycleStatus.ACTIVE.getCode().equals(rule.getStatus()))
                .filter(rule -> RuleFormat.JSON.getCode().equals(rule.getRuleFormat()))
                .sorted(Comparator.comparing((RuleDefinition rule) -> rule.getPriority() == null ? 0 : rule.getPriority()).reversed())
                .toList();
    }

    private JsonNode parseRuleContent(RuleDefinition rule) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rule.getRuleContent());
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("JSON rule content must be an object: " + rule.getRuleCode());
            }
            return root;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON rule content: " + rule.getRuleCode(), e);
        }
    }

    private List<RuleConditionEvaluation> evaluateConditions(JsonNode conditions,
                                                              Map<String, Object> factors,
                                                              RuleDefinition rule) {
        if (!conditions.isArray()) {
            throw new IllegalArgumentException("JSON rule conditions must be an array: " + rule.getRuleCode());
        }
        List<RuleConditionEvaluation> evaluations = new ArrayList<>();
        for (JsonNode condition : conditions) {
            if (!condition.isObject() || !StringUtils.hasText(condition.path("field").asText())
                    || condition.path("value").isMissingNode()) {
                throw new IllegalArgumentException("Invalid JSON rule condition: " + rule.getRuleCode());
            }
            String field = condition.path("field").asText();
            String operator = condition.path("operator").asText("eq");
            Object actual = factors == null ? null : factors.get(field);
            JsonNode expected = condition.path("value");
            validateCondition(operator, expected, rule);
            String status;
            if (actual == null) {
                status = "MISSING";
            } else if (isUnavailable(actual) || isInvalidNumericFactor(operator, actual)) {
                status = "INVALID";
            } else {
                status = matchesCondition(operator, actual, expected) ? "MATCHED" : "NOT_MATCHED";
            }
            evaluations.add(new RuleConditionEvaluation(field, operator, actual,
                    OBJECT_MAPPER.convertValue(expected, Object.class), status));
        }
        return List.copyOf(evaluations);
    }

    private void validateCondition(String operator, JsonNode expected, RuleDefinition rule) {
        switch (operator) {
            case "eq", "ne" -> {
                if (expected.isNull() || expected.isContainerNode()) {
                    throw new IllegalArgumentException("Invalid scalar JSON rule value: " + rule.getRuleCode());
                }
            }
            case "gt", "gte", "lt", "lte" -> {
                if (expected.isNull() || expected.isContainerNode()) {
                    throw new IllegalArgumentException("Invalid numeric JSON rule value: " + rule.getRuleCode());
                }
                try {
                    new BigDecimal(expected.asText());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid numeric JSON rule value: " + rule.getRuleCode(), e);
                }
            }
            case "in" -> {
                if (!expected.isArray() || expected.isEmpty()) {
                    throw new IllegalArgumentException("JSON rule in value must be an array: " + rule.getRuleCode());
                }
                for (JsonNode item : expected) {
                    if (item.isNull() || item.isContainerNode()) {
                        throw new IllegalArgumentException("JSON rule in values must be scalar: " + rule.getRuleCode());
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported JSON rule operator: " + operator);
        }
    }

    private boolean isUnavailable(Object actual) {
        if (!(actual instanceof CharSequence text)) {
            return false;
        }
        String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() || "unknown".equals(normalized)
                || "suspended".equals(normalized)
                || "suspended_or_missing".equals(normalized)
                || "insufficient_data".equals(normalized)
                || "data_insufficient".equals(normalized);
    }

    private boolean isInvalidNumericFactor(String operator, Object actual) {
        if (!List.of("gt", "gte", "lt", "lte").contains(operator)) {
            return false;
        }
        try {
            new BigDecimal(String.valueOf(actual));
            return false;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private boolean matchesCondition(String operator, Object actual, JsonNode expected) {
        return switch (operator) {
            case "eq" -> compareAsString(actual, expected) == 0;
            case "ne" -> compareAsString(actual, expected) != 0;
            case "gt" -> compareAsDecimal(actual, expected) > 0;
            case "gte" -> compareAsDecimal(actual, expected) >= 0;
            case "lt" -> compareAsDecimal(actual, expected) < 0;
            case "lte" -> compareAsDecimal(actual, expected) <= 0;
            case "in" -> containsExpectedValue(actual, expected);
            default -> throw new IllegalArgumentException("Unsupported JSON rule operator: " + operator);
        };
    }

    private int compareAsString(Object actual, JsonNode expected) {
        if (actual == null) {
            return expected.isNull() ? 0 : -1;
        }
        return String.valueOf(actual).compareTo(expected.asText());
    }

    private int compareAsDecimal(Object actual, JsonNode expected) {
        return new BigDecimal(String.valueOf(actual)).compareTo(new BigDecimal(expected.asText()));
    }

    private boolean containsExpectedValue(Object actual, JsonNode expected) {
        if (!expected.isArray()) {
            return false;
        }
        for (JsonNode item : expected) {
            if (compareAsString(actual, item) == 0) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal decimalAction(JsonNode actions, String field) {
        JsonNode value = actions.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.asText());
    }

    private BigDecimal normalizeNonNegative(BigDecimal value) {
        return value.max(BigDecimal.ZERO).stripTrailingZeros();
    }
}
