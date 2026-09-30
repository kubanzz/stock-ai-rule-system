-- 将 V5 生成的受限 JSON 规则转换为生产 Drools 规则。
--
-- JSON 仍保留在 candidate_rule.proposed_content，作为 AI 候选规则的交换格式。
-- rule_definition 是生产规则目录，因此只在这里保存可直接由 Drools 执行的内容。
-- 保留原有 status、enabled、priority 和 version，避免迁移改变审核状态或规则排序。

UPDATE rule_definition
SET rule_format = 'drools',
    rule_content = 'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "CR_TREND_BEAR_GUARD_001"\nsalience 100\nwhen\n    $f : StockFactorFact()\n    eval($f.matches("data_status", "eq", "normal"))\n    eval($f.matches("short_term_trend", "eq", "down") || $f.matches("short_term_trend", "eq", "strong_down"))\n    eval($f.matches("technical_status", "eq", "bearish"))\nthen\n    $f.addBearishScore(new BigDecimal("55"));\n    $f.addRiskScore(new BigDecimal("15"));\n    $f.addTriggeredRule("CR_TREND_BEAR_GUARD_001");\n    $f.addExplanation("趋势与技术状态同步偏弱，信号偏向防守并提示回撤风险；仅供研究和辅助决策，不构成投资建议。");\nend'
WHERE rule_code = 'CR_TREND_BEAR_GUARD_001'
  AND rule_format = 'json';

UPDATE rule_definition
SET rule_format = 'drools',
    rule_content = 'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "CR_OVERSOLD_RISK_GUARD_001"\nsalience 90\nwhen\n    $f : StockFactorFact()\n    eval($f.matches("data_status", "eq", "normal"))\n    eval($f.matches("technical_status", "eq", "oversold"))\n    eval($f.matches("rsi14", "lte", "30"))\nthen\n    $f.addBearishScore(new BigDecimal("10"));\n    $f.addRiskScore(new BigDecimal("35"));\n    $f.addTriggeredRule("CR_OVERSOLD_RISK_GUARD_001");\n    $f.addExplanation("RSI 进入超卖区仅用于风险提示，不将超卖直接解释为反转或买入信号；仅供研究和辅助决策，不构成投资建议。");\nend'
WHERE rule_code = 'CR_OVERSOLD_RISK_GUARD_001'
  AND rule_format = 'json';

UPDATE rule_definition
SET rule_format = 'drools',
    rule_content = 'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "CR_SIDEWAYS_MACD_RECOVERY_001"\nsalience 80\nwhen\n    $f : StockFactorFact()\n    eval($f.matches("data_status", "eq", "normal"))\n    eval($f.matches("short_term_trend", "eq", "sideways"))\n    eval($f.matches("macd_histogram", "gt", "0"))\n    eval($f.matches("technical_status", "eq", "neutral"))\nthen\n    $f.addBullishScore(new BigDecimal("55"));\n    $f.addRiskScore(new BigDecimal("10"));\n    $f.addTriggeredRule("CR_SIDEWAYS_MACD_RECOVERY_001");\n    $f.addExplanation("震荡状态下 MACD 柱转正，给出偏看涨的辅助信号并保留风险分；仅供研究和辅助决策，不构成投资建议。");\nend'
WHERE rule_code = 'CR_SIDEWAYS_MACD_RECOVERY_001'
  AND rule_format = 'json';

UPDATE rule_definition
SET rule_format = 'drools',
    rule_content = 'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "CR_VOLUME_MACD_DIVERGENCE_001"\nsalience 70\nwhen\n    $f : StockFactorFact()\n    eval($f.matches("data_status", "eq", "normal"))\n    eval($f.matches("volume_ratio_5d", "gte", "1.30"))\n    eval($f.matches("macd_histogram", "lt", "0"))\nthen\n    $f.addBearishScore(new BigDecimal("25"));\n    $f.addRiskScore(new BigDecimal("35"));\n    $f.addTriggeredRule("CR_VOLUME_MACD_DIVERGENCE_001");\n    $f.addExplanation("成交量相对放大但 MACD 柱为负，提示放量下行或冲高回落风险；仅供研究和辅助决策，不构成投资建议。");\nend'
WHERE rule_code = 'CR_VOLUME_MACD_DIVERGENCE_001'
  AND rule_format = 'json';

UPDATE rule_definition
SET rule_format = 'drools',
    rule_content = 'import java.math.BigDecimal;\nimport com.jx.tracker.rule.engine.StockFactorFact;\n\nrule "CR_WEAK_VOLUME_CONTINUATION_001"\nsalience 60\nwhen\n    $f : StockFactorFact()\n    eval($f.matches("data_status", "eq", "normal"))\n    eval($f.matches("short_term_trend", "eq", "down"))\n    eval($f.matches("volume_status", "eq", "normal") || $f.matches("volume_status", "eq", "low"))\n    eval($f.matches("macd_histogram", "lt", "0"))\nthen\n    $f.addBearishScore(new BigDecimal("35"));\n    $f.addRiskScore(new BigDecimal("10"));\n    $f.addTriggeredRule("CR_WEAK_VOLUME_CONTINUATION_001");\n    $f.addExplanation("下行趋势、量能未明显放大且 MACD 柱为负，提示弱势延续风险；仅供研究和辅助决策，不构成投资建议。");\nend'
WHERE rule_code = 'CR_WEAK_VOLUME_CONTINUATION_001'
  AND rule_format = 'json';
