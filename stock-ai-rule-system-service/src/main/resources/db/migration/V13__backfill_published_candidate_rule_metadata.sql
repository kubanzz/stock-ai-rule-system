-- 补齐历史 AI 候选发布后形成的生产规则元数据。
-- 仅处理已发布的五条种子候选；人工填写的名称、描述和业务类型保持不变。
-- 不修改 Drools 内容、执行状态或规则版本。
UPDATE rule_definition AS rule
INNER JOIN candidate_rule AS candidate
        ON candidate.candidate_code = rule.rule_code
       AND candidate.target_rule_code = rule.rule_code
       AND candidate.status = 'published'
INNER JOIN (
    SELECT 'CR_TREND_BEAR_GUARD_001' AS rule_code,
           '趋势与技术同步偏弱防守' AS rule_name,
           '数据正常、短期趋势下行或强下行且技术状态偏弱时，增加看跌和风险证据，提示防守观察与回撤风险；仅供研究和辅助决策，不构成投资建议。' AS description,
           'risk_guard' AS rule_type
    UNION ALL
    SELECT 'CR_OVERSOLD_RISK_GUARD_001',
           '超卖区风险提示',
           '数据正常、技术状态超卖且 RSI14 不高于 30 时增加风险提示，不将超卖直接解释为反转或买入信号；仅供研究和辅助决策，不构成投资建议。',
           'risk_guard'
    UNION ALL
    SELECT 'CR_SIDEWAYS_MACD_RECOVERY_001',
           '震荡区 MACD 修复',
           '数据正常、短期趋势震荡、技术状态中性且 MACD 柱为正时，增加偏看涨辅助证据并保留风险提示；仅供研究和辅助决策，不构成投资建议。',
           'technical'
    UNION ALL
    SELECT 'CR_VOLUME_MACD_DIVERGENCE_001',
           '放量与 MACD 背离风险',
           '数据正常、5 日量比不低于 1.30 且 MACD 柱为负时，增加看跌和风险证据，提示放量下行或冲高回落风险；仅供研究和辅助决策，不构成投资建议。',
           'technical'
    UNION ALL
    SELECT 'CR_WEAK_VOLUME_CONTINUATION_001',
           '弱势趋势延续',
           '数据正常、短期趋势下行、量能正常或偏低且 MACD 柱为负时，增加看跌和风险证据，提示弱势延续风险；仅供研究和辅助决策，不构成投资建议。',
           'trend'
) AS metadata ON metadata.rule_code = rule.rule_code
SET rule.rule_name = CASE
        WHEN rule.rule_name IS NULL OR TRIM(rule.rule_name) = '' OR rule.rule_name = rule.rule_code
            THEN metadata.rule_name
        ELSE rule.rule_name
    END,
    rule.description = CASE
        WHEN rule.description IS NULL OR TRIM(rule.description) = ''
            THEN metadata.description
        ELSE rule.description
    END,
    rule.rule_type = CASE
        WHEN rule.rule_type = 'ai_candidate' THEN metadata.rule_type
        ELSE rule.rule_type
    END
WHERE rule.rule_name IS NULL
   OR TRIM(rule.rule_name) = ''
   OR rule.rule_name = rule.rule_code
   OR rule.description IS NULL
   OR TRIM(rule.description) = ''
   OR rule.rule_type = 'ai_candidate';
