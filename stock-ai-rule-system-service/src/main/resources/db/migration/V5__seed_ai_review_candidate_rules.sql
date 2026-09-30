-- 基于当前已落库的 2026-09-28 技术因子样本生成的候选规则种子。
-- 这些记录从候选状态开始，仍须回测、人工审核后才能发布为 active。
-- 数据依据：stock_daily_quote（aktools/akshare）及 stock_factor_daily；
-- 当前正常因子样本量有限，不能据此推断收益或保证预测准确性。

INSERT INTO ai_review_report (
    symbol, review_date, diagnosis, suggestions, model_name, risk_disclaimer
)
SELECT
    NULL,
    '2026-09-29',
    '基于 2026-09-28 已落库的正常技术因子样本生成 5 条候选规则。样本窗口较短，候选规则只作为股票研究和辅助决策信号，必须完成回测和人工审核。',
    JSON_OBJECT(
        'diagnosis', '基于 2026-09-28 已落库的正常技术因子样本生成 5 条候选规则。',
        'related_rules', JSON_ARRAY(),
        'need_backtest', TRUE,
        'risk', '本系统输出仅用于股票研究和辅助决策，不构成投资建议，不代表确定性预测，也不保证收益。',
        'suggestions', JSON_ARRAY(
            JSON_OBJECT('type', 'add_risk_guard', 'rule_id', 'CR_TREND_BEAR_GUARD_001', 'need_backtest', TRUE),
            JSON_OBJECT('type', 'add_risk_guard', 'rule_id', 'CR_OVERSOLD_RISK_GUARD_001', 'need_backtest', TRUE),
            JSON_OBJECT('type', 'add_filter', 'rule_id', 'CR_SIDEWAYS_MACD_RECOVERY_001', 'need_backtest', TRUE),
            JSON_OBJECT('type', 'add_risk_guard', 'rule_id', 'CR_VOLUME_MACD_DIVERGENCE_001', 'need_backtest', TRUE),
            JSON_OBJECT('type', 'add_filter', 'rule_id', 'CR_WEAK_VOLUME_CONTINUATION_001', 'need_backtest', TRUE)
        )
    ),
    'historical-rule-review-v1',
    '本系统输出仅用于股票研究和辅助决策，不构成投资建议，不代表确定性预测，也不保证收益。'
WHERE NOT EXISTS (
    SELECT 1 FROM ai_review_report WHERE model_name = 'historical-rule-review-v1'
      AND review_date = '2026-09-29'
);

SET @seed_review_id = (
    SELECT id FROM ai_review_report
    WHERE model_name = 'historical-rule-review-v1' AND review_date = '2026-09-29'
    ORDER BY id DESC LIMIT 1
);

INSERT INTO rule_definition (
    rule_code, rule_name, rule_type, rule_content, rule_format, version,
    status, enabled, priority, created_by, updated_by
)
VALUES
(
    'CR_TREND_BEAR_GUARD_001', '趋势与技术同步偏弱防守规则', 'ai_candidate',
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"short_term_trend","operator":"in","value":["down","strong_down"]},{"field":"technical_status","operator":"eq","value":"bearish"}],"actions":{"bearish_score":55,"risk_score":15,"explanation":"趋势与技术状态同步偏弱，信号偏向防守并提示回撤风险。"}}',
    'json', 'v1', 'draft', 0, 100, 'historical-review', 'historical-review'
),
(
    'CR_OVERSOLD_RISK_GUARD_001', '超卖区风险提示规则', 'ai_candidate',
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"technical_status","operator":"eq","value":"oversold"},{"field":"rsi14","operator":"lte","value":30}],"actions":{"bearish_score":10,"risk_score":35,"explanation":"RSI 进入超卖区仅用于风险提示，不将超卖直接解释为反转或买入信号。"}}',
    'json', 'v1', 'draft', 0, 90, 'historical-review', 'historical-review'
),
(
    'CR_SIDEWAYS_MACD_RECOVERY_001', '震荡区 MACD 修复规则', 'ai_candidate',
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"short_term_trend","operator":"eq","value":"sideways"},{"field":"macd_histogram","operator":"gt","value":0},{"field":"technical_status","operator":"eq","value":"neutral"}],"actions":{"bullish_score":55,"risk_score":10,"explanation":"震荡状态下 MACD 柱转正，给出偏看涨的辅助信号并保留风险分。"}}',
    'json', 'v1', 'draft', 0, 80, 'historical-review', 'historical-review'
),
(
    'CR_VOLUME_MACD_DIVERGENCE_001', '放量与 MACD 背离风险规则', 'ai_candidate',
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"volume_ratio_5d","operator":"gte","value":1.30},{"field":"macd_histogram","operator":"lt","value":0}],"actions":{"bearish_score":25,"risk_score":35,"explanation":"成交量相对放大但 MACD 柱为负，提示放量下行或冲高回落风险。"}}',
    'json', 'v1', 'draft', 0, 70, 'historical-review', 'historical-review'
),
(
    'CR_WEAK_VOLUME_CONTINUATION_001', '弱势趋势延续规则', 'ai_candidate',
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"short_term_trend","operator":"eq","value":"down"},{"field":"volume_status","operator":"in","value":["normal","low"]},{"field":"macd_histogram","operator":"lt","value":0}],"actions":{"bearish_score":35,"risk_score":10,"explanation":"下行趋势、量能未明显放大且 MACD 柱为负，提示弱势延续风险。"}}',
    'json', 'v1', 'draft', 0, 60, 'historical-review', 'historical-review'
)
ON DUPLICATE KEY UPDATE
    rule_name = VALUES(rule_name),
    rule_type = VALUES(rule_type),
    priority = VALUES(priority);

INSERT INTO candidate_rule (
    candidate_code, source_review_id, source, target_rule_code, change_type,
    original_content, proposed_content, reason, status, backtest_status, approval_status
)
VALUES
(
    'CR_TREND_BEAR_GUARD_001', @seed_review_id, 'AI', 'CR_TREND_BEAR_GUARD_001', 'add_risk_guard',
    NULL,
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"short_term_trend","operator":"in","value":["down","strong_down"]},{"field":"technical_status","operator":"eq","value":"bearish"}],"actions":{"bearish_score":55,"risk_score":15,"explanation":"趋势与技术状态同步偏弱，信号偏向防守并提示回撤风险。"}}',
    '2026-09-28 样本中 000001.SZ、002063.SZ、600009.SH 同时呈现下行趋势和 bearish 技术状态；候选规则用于防守提示。样本有限，须回测和人工审核。',
    'candidate', 'pending', 'pending'
),
(
    'CR_OVERSOLD_RISK_GUARD_001', @seed_review_id, 'AI', 'CR_OVERSOLD_RISK_GUARD_001', 'add_risk_guard',
    NULL,
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"technical_status","operator":"eq","value":"oversold"},{"field":"rsi14","operator":"lte","value":30}],"actions":{"bearish_score":10,"risk_score":35,"explanation":"RSI 进入超卖区仅用于风险提示，不将超卖直接解释为反转或买入信号。"}}',
    '2026-09-28 样本中 600519.SH 的 RSI14 为 17.7732 且技术状态为 oversold；候选规则只增加风险提示，不把超卖视为买入确认。样本有限，须回测和人工审核。',
    'candidate', 'pending', 'pending'
),
(
    'CR_SIDEWAYS_MACD_RECOVERY_001', @seed_review_id, 'AI', 'CR_SIDEWAYS_MACD_RECOVERY_001', 'add_filter',
    NULL,
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"short_term_trend","operator":"eq","value":"sideways"},{"field":"macd_histogram","operator":"gt","value":0},{"field":"technical_status","operator":"eq","value":"neutral"}],"actions":{"bullish_score":55,"risk_score":10,"explanation":"震荡状态下 MACD 柱转正，给出偏看涨的辅助信号并保留风险分。"}}',
    '2026-09-28 样本中 000002.SZ、300454.SZ 处于 sideways/neutral 且 MACD 柱为正；候选规则用于观察修复信号。样本有限，须回测和人工审核。',
    'candidate', 'pending', 'pending'
),
(
    'CR_VOLUME_MACD_DIVERGENCE_001', @seed_review_id, 'AI', 'CR_VOLUME_MACD_DIVERGENCE_001', 'add_risk_guard',
    NULL,
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"volume_ratio_5d","operator":"gte","value":1.30},{"field":"macd_histogram","operator":"lt","value":0}],"actions":{"bearish_score":25,"risk_score":35,"explanation":"成交量相对放大但 MACD 柱为负，提示放量下行或冲高回落风险。"}}',
    '2026-09-28 样本中 002714.SZ 的量比为 1.6538、MACD 柱为 -0.2497；候选规则用于识别量价背离风险。样本有限，须回测和人工审核。',
    'candidate', 'pending', 'pending'
),
(
    'CR_WEAK_VOLUME_CONTINUATION_001', @seed_review_id, 'AI', 'CR_WEAK_VOLUME_CONTINUATION_001', 'add_filter',
    NULL,
    '{"conditions":[{"field":"data_status","operator":"eq","value":"normal"},{"field":"short_term_trend","operator":"eq","value":"down"},{"field":"volume_status","operator":"in","value":["normal","low"]},{"field":"macd_histogram","operator":"lt","value":0}],"actions":{"bearish_score":35,"risk_score":10,"explanation":"下行趋势、量能未明显放大且 MACD 柱为负，提示弱势延续风险。"}}',
    '2026-09-28 样本中 000001.SZ、002063.SZ、600009.SH 满足弱势趋势、正常/低量和 MACD 柱为负；候选规则用于弱势延续提示。样本有限，须回测和人工审核。',
    'candidate', 'pending', 'pending'
)
ON DUPLICATE KEY UPDATE
    source_review_id = COALESCE(candidate_rule.source_review_id, VALUES(source_review_id)),
    proposed_content = VALUES(proposed_content),
    reason = VALUES(reason);
