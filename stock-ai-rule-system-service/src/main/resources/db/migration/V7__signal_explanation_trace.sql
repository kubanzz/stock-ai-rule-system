-- Existing rows intentionally retain NULL: their historic input and rule versions cannot be reconstructed.
ALTER TABLE stock_signal_daily
    ADD COLUMN trace_json JSON NULL COMMENT '信号生成时冻结的因子、规则命中和评分轨迹' AFTER risk_disclaimer;

ALTER TABLE stock_signal_daily_history
    ADD COLUMN trace_json JSON NULL COMMENT '该历史版本生成时的完整解释轨迹' AFTER risk_disclaimer;

-- Preserve the original hash for pre-trace rows, including their immutable
-- history. New rows include trace_json so evidence changes create a version.
ALTER TABLE stock_signal_daily
    DROP COLUMN signal_content_fingerprint,
    ADD COLUMN signal_content_fingerprint CHAR(64) CHARACTER SET ascii
        GENERATED ALWAYS AS (
            CASE WHEN trace_json IS NULL THEN
                SHA2(CAST(JSON_ARRAY(
                    symbol, signal_date, `signal`, signal_direction, signal_level,
                    bullish_score, bearish_score, risk_score, confidence,
                    triggered_rules, explanation, risk_disclaimer
                ) AS CHAR), 256)
            ELSE
                SHA2(CAST(JSON_ARRAY(
                    symbol, signal_date, `signal`, signal_direction, signal_level,
                    bullish_score, bearish_score, risk_score, confidence,
                    triggered_rules, explanation, risk_disclaimer, trace_json
                ) AS CHAR), 256)
            END
        ) STORED COMMENT '含解释轨迹的正式信号确定性 SHA-256 指纹';
