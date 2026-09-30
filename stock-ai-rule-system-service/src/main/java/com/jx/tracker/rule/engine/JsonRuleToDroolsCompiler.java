package com.jx.tracker.rule.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Compiles the restricted JSON candidate format into the single production
 * Drools format. The generated DRL only calls whitelisted methods on
 * {@link StockFactorFact}; arbitrary code from an AI response is never copied
 * into a production rule.
 */
@Component
public class JsonRuleToDroolsCompiler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SAFE_RULE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    public String compile(String ruleCode, String content) {
        if (ruleCode == null || !SAFE_RULE_CODE.matcher(ruleCode).matches()) {
            throw new ServiceException("规则编码包含不支持的字符：" + ruleCode);
        }
        if (content == null || content.isBlank()) {
            throw new ServiceException("候选规则内容不能为空");
        }
        final JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(content);
        } catch (Exception e) {
            throw new ServiceException("候选规则内容必须是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new ServiceException("候选规则内容必须是 JSON 对象");
        }

        JsonNode conditions = root.path("conditions");
        if (!conditions.isArray() || conditions.isEmpty()) {
            throw new ServiceException("候选规则缺少 conditions 数组或数组不能为空");
        }
        List<String> predicates = new ArrayList<>();
        for (JsonNode condition : conditions) {
            predicates.add(compileCondition(condition));
        }

        JsonNode actions = root.path("actions");
        if (!actions.isObject()) {
            throw new ServiceException("候选规则必须包含 actions 对象");
        }
        if (!hasScoreAction(actions)) {
            throw new ServiceException("候选规则 actions 至少需要一个分值字段：bullish_score、bearish_score 或 risk_score");
        }
        StringBuilder drl = new StringBuilder(1024);
        drl.append("package com.jx.tracker.rule.generated;\n")
                .append("import java.math.BigDecimal;\n")
                .append("import com.jx.tracker.rule.engine.StockFactorFact;\n\n")
                .append("rule \"").append(escape(ruleCode)).append("\"\n")
                .append("when\n")
                .append("    $f : StockFactorFact()\n");
        for (String predicate : predicates) {
            drl.append("    eval(").append(predicate).append(")\n");
        }
        drl.append("then\n");
        appendScoreAction(drl, actions, "bullish_score", "addBullishScore");
        appendScoreAction(drl, actions, "bearish_score", "addBearishScore");
        appendScoreAction(drl, actions, "risk_score", "addRiskScore");
        drl.append("    $f.addTriggeredRule(\"").append(escape(ruleCode)).append("\");\n");
        String explanation = optionalScalarText(actions.get("explanation"));
        if (explanation != null && !explanation.isBlank()) {
            drl.append("    $f.addExplanation(\"").append(escape(explanation)).append("\");\n");
        }
        drl.append("end\n");
        return drl.toString();
    }

    private String compileCondition(JsonNode condition) {
        if (condition == null || !condition.isObject()) {
            throw new ServiceException("规则条件必须是对象");
        }
        String field = scalarText(condition.get("field"), "condition.field");
        String operator = scalarText(condition.get("operator"), "condition.operator");
        JsonNode value = condition.get("value");
        if (field == null || operator == null || value == null || value.isNull()) {
            throw new ServiceException("规则条件缺少 field、operator 或 value");
        }
        if (!SAFE_RULE_CODE.matcher(field).matches()) {
            throw new ServiceException("规则字段包含不支持的字符：" + field);
        }
        return switch (operator) {
            case "eq", "ne", "gt", "gte", "lt", "lte" ->
                    "$f.matches(\"" + escape(field) + "\",\"" + escape(operator)
                            + "\",\"" + escape(scalarText(value, "condition.value")) + "\")";
            case "in" -> compileInCondition(field, value);
            default -> throw new ServiceException("不支持的规则操作符：" + operator);
        };
    }

    private String compileInCondition(String field, JsonNode value) {
        if (!value.isArray() || value.isEmpty()) {
            throw new ServiceException("in 操作符的 value 必须是非空数组");
        }
        List<String> alternatives = new ArrayList<>();
        for (JsonNode item : value) {
            String expected = scalarText(item, "condition.value[]");
            alternatives.add("$f.matches(\"" + escape(field) + "\",\"eq\",\""
                    + escape(expected) + "\")");
        }
        return String.join(" || ", alternatives);
    }

    private void appendScoreAction(StringBuilder drl, JsonNode actions,
                                   String jsonField, String method) {
        JsonNode value = actions.get(jsonField);
        if (value == null || value.isNull()) {
            return;
        }
        String score = scalarText(value, "actions." + jsonField);
        try {
            new BigDecimal(score);
        } catch (NumberFormatException e) {
            throw new ServiceException("动作分数必须是数字：" + jsonField);
        }
        drl.append("    $f.").append(method).append("(new BigDecimal(\"")
                .append(escape(score)).append("\"));\n");
    }

    private boolean hasScoreAction(JsonNode actions) {
        return hasValue(actions, "bullish_score")
                || hasValue(actions, "bearish_score")
                || hasValue(actions, "risk_score");
    }

    private boolean hasValue(JsonNode object, String field) {
        JsonNode value = object.get(field);
        return value != null && !value.isNull();
    }

    private String scalarText(JsonNode node, String path) {
        if (node == null || !node.isValueNode() || node.isNull()) {
            throw new ServiceException(path + " 必须是标量值");
        }
        return node.asText();
    }

    private String optionalScalarText(JsonNode node) {
        return node == null || node.isNull() ? null : scalarText(node, "actions.explanation");
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }
}
