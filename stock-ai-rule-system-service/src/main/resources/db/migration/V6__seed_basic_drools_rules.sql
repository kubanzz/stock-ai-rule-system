-- 基础 Drools 规则：用于信号生成的可解释辅助证据。
-- 规则使用当前技术因子字段，仅输出看涨、看跌和风险分，不代表确定性预测或收益保证。
-- 这些规则默认 active，便于系统立即应用；上线后仍应结合回测和人工审核持续调整。

-- 兼容已在本机手动导入同一份种子后，Flyway 首次执行 V6 的情况。
SET @description_column_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'rule_definition'
      AND column_name = 'description'
);
SET @description_column_ddl = IF(
    @description_column_exists = 0,
    'ALTER TABLE rule_definition ADD COLUMN description VARCHAR(512) AFTER rule_name',
    'DO 0'
);
PREPARE description_column_statement FROM @description_column_ddl;
EXECUTE description_column_statement;
DEALLOCATE PREPARE description_column_statement;

INSERT INTO rule_definition (
    rule_code, rule_name, description, rule_type, rule_content, rule_format, version,
    status, enabled, priority, created_by, updated_by
)
VALUES
(
    'R_DROOLS_TREND_VOLUME_CONFIRM_001',
    '趋势放量确认',
    '短期趋势向上且成交量异常放大时，增加偏看涨辅助证据，同时保留少量风险提示。',
    'trend',
    'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "R_DROOLS_TREND_VOLUME_CONFIRM_001"\nsalience 100\nwhen\n    $f : StockFactorFact(\n        shortTermTrend in ("strong_up", "up"),\n        volumeStatus == "abnormal_high"\n    )\nthen\n    $f.addBullishScore(new BigDecimal("35"));\n    $f.addRiskScore(new BigDecimal("10"));\n    $f.addTriggeredRule("R_DROOLS_TREND_VOLUME_CONFIRM_001");\n    $f.addExplanation("趋势偏强且成交量异常放大，形成偏看涨辅助信号，同时保留风险提示；仅供研究和辅助决策，不构成投资建议。");\nend',
    'drools', 'v1', 'active', 1, 100, 'system-seed', 'system-seed'
),
(
    'R_DROOLS_OVERHEAT_RISK_001',
    '高位过热风险',
    'RSI 处于高位且成交量异常放大时，提示短线过热和回撤风险，不将其解释为确定性反转。',
    'risk_guard',
    'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "R_DROOLS_OVERHEAT_RISK_001"\nsalience 90\nwhen\n    $f : StockFactorFact(\n        rsi >= new BigDecimal("85"),\n        volumeStatus == "abnormal_high"\n    )\nthen\n    $f.addBearishScore(new BigDecimal("10"));\n    $f.addRiskScore(new BigDecimal("45"));\n    $f.addTriggeredRule("R_DROOLS_OVERHEAT_RISK_001");\n    $f.addExplanation("RSI 处于高位且成交量异常放大，提示短线过热和回撤风险；仅供研究和辅助决策，不构成投资建议。");\nend',
    'drools', 'v1', 'active', 1, 90, 'system-seed', 'system-seed'
),
(
    'R_DROOLS_BEARISH_GUARD_001',
    '趋势技术同步偏弱防守',
    '短期趋势向下且技术状态偏弱时，增加看跌和风险证据，提示采取防守观察。',
    'risk_guard',
    'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "R_DROOLS_BEARISH_GUARD_001"\nsalience 80\nwhen\n    $f : StockFactorFact(\n        shortTermTrend in ("strong_down", "down"),\n        technicalStatus == "bearish"\n    )\nthen\n    $f.addBearishScore(new BigDecimal("35"));\n    $f.addRiskScore(new BigDecimal("30"));\n    $f.addTriggeredRule("R_DROOLS_BEARISH_GUARD_001");\n    $f.addExplanation("趋势与技术状态同步偏弱，提示防守并关注回撤风险；仅供研究和辅助决策，不构成投资建议。");\nend',
    'drools', 'v1', 'active', 1, 80, 'system-seed', 'system-seed'
),
(
    'R_DROOLS_MACD_VOLUME_DIVERGENCE_001',
    '放量 MACD 转弱风险',
    '成交量明显放大但 MACD 柱为负时，提示量价背离和冲高回落风险。',
    'technical',
    'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "R_DROOLS_MACD_VOLUME_DIVERGENCE_001"\nsalience 70\nwhen\n    $f : StockFactorFact(\n        volumeRatio >= new BigDecimal("1.30"),\n        macd < BigDecimal.ZERO\n    )\nthen\n    $f.addBearishScore(new BigDecimal("25"));\n    $f.addRiskScore(new BigDecimal("35"));\n    $f.addTriggeredRule("R_DROOLS_MACD_VOLUME_DIVERGENCE_001");\n    $f.addExplanation("成交量相对放大但 MACD 柱为负，提示量价背离或冲高回落风险；仅供研究和辅助决策，不构成投资建议。");\nend',
    'drools', 'v1', 'active', 1, 70, 'system-seed', 'system-seed'
),
(
    'R_DROOLS_OVERSOLD_NOTICE_001',
    '超卖区风险提示',
    'RSI 进入超卖区时增加风险提示，不把超卖状态直接解释为反转或买入信号。',
    'risk_guard',
    'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "R_DROOLS_OVERSOLD_NOTICE_001"\nsalience 60\nwhen\n    $f : StockFactorFact(\n        rsi <= new BigDecimal("30"),\n        technicalStatus == "oversold"\n    )\nthen\n    $f.addBearishScore(new BigDecimal("10"));\n    $f.addRiskScore(new BigDecimal("35"));\n    $f.addTriggeredRule("R_DROOLS_OVERSOLD_NOTICE_001");\n    $f.addExplanation("RSI 进入超卖区，仅增加风险提示，不将超卖直接解释为反转或买入信号；仅供研究和辅助决策，不构成投资建议。");\nend',
    'drools', 'v1', 'active', 1, 60, 'system-seed', 'system-seed'
)
ON DUPLICATE KEY UPDATE
    rule_name = VALUES(rule_name),
    description = VALUES(description),
    rule_type = VALUES(rule_type),
    rule_content = VALUES(rule_content),
    rule_format = VALUES(rule_format),
    version = VALUES(version),
    status = VALUES(status),
    enabled = VALUES(enabled),
    priority = VALUES(priority),
    updated_by = VALUES(updated_by);
