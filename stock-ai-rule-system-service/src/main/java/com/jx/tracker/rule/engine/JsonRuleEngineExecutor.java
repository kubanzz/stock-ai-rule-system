package com.jx.tracker.rule.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.enums.RuleFormat;
import com.jx.tracker.domain.enums.RuleLifecycleStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class JsonRuleEngineExecutor implements RuleEngineExecutor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public RuleExecutionResult execute(RuleExecutionRequest request) {
        BigDecimal bullishScore = BigDecimal.ZERO;
        BigDecimal bearishScore = BigDecimal.ZERO;
        BigDecimal riskScore = BigDecimal.ZERO;
        List<String> triggeredRules = new ArrayList<>();
        List<String> explanations = new ArrayList<>();

        List<RuleDefinition> rules = request.rules() == null ? List.of() : request.rules();
        for (RuleDefinition rule : executableRules(rules)) {
            JsonNode root = parseRuleContent(rule);
            if (!matches(root.path("conditions"), request.factors())) {
                continue;
            }

            JsonNode actions = root.path("actions");
            bullishScore = bullishScore.add(decimalAction(actions, "bullish_score"));
            bearishScore = bearishScore.add(decimalAction(actions, "bearish_score"));
            riskScore = riskScore.add(decimalAction(actions, "risk_score"));
            triggeredRules.add(rule.getRuleCode());
            if (StringUtils.hasText(actions.path("explanation").asText())) {
                explanations.add(actions.path("explanation").asText());
            }
        }

        return new RuleExecutionResult(
                normalizeNonNegative(bullishScore),
                normalizeNonNegative(bearishScore),
                normalizeNonNegative(riskScore),
                List.copyOf(triggeredRules),
                List.copyOf(explanations)
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
            return OBJECT_MAPPER.readTree(rule.getRuleContent());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON rule content: " + rule.getRuleCode(), e);
        }
    }

    private boolean matches(JsonNode conditions, Map<String, Object> factors) {
        if (!conditions.isArray()) {
            return true;
        }
        for (JsonNode condition : conditions) {
            if (!matchesCondition(condition, factors)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(JsonNode condition, Map<String, Object> factors) {
        String field = condition.path("field").asText();
        String operator = condition.path("operator").asText("eq");
        Object actual = factors == null ? null : factors.get(field);
        JsonNode expected = condition.path("value");

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
        if (actual == null || expected.isMissingNode() || expected.isNull()) {
            return -1;
        }
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
