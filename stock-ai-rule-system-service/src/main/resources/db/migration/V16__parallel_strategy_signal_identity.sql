-- 多个方案可同时启用；运行端必须逐方案使用自己的冻结快照。
ALTER TABLE rule_strategy
    DROP INDEX uk_rule_strategy_active_slot,
    DROP COLUMN active_slot;

-- 历史方案归属无法可靠重建，保留显式 legacy 命名空间和原 signal_id。
-- regular/backfill 只是生成方式，同一方案版本的同日信号仍是同一身份。
ALTER TABLE stock_signal_daily
    ADD COLUMN strategy_code VARCHAR(64) NOT NULL DEFAULT 'LEGACY' COMMENT '生成时冻结的方案编码；旧记录 LEGACY' AFTER signal_date,
    ADD COLUMN strategy_version VARCHAR(32) NOT NULL DEFAULT 'legacy' COMMENT '生成时冻结的方案版本；旧记录 legacy' AFTER strategy_code,
    ADD COLUMN strategy_name VARCHAR(128) NULL COMMENT '信号生成时冻结的方案名称；旧记录为空' AFTER strategy_version,
    DROP INDEX uk_stock_signal_daily_symbol_signal_date,
    ADD UNIQUE KEY uk_stock_signal_daily_strategy_identity (symbol, signal_date, strategy_code, strategy_version),
    ADD KEY idx_stock_signal_daily_strategy_date (strategy_code, strategy_version, signal_date);

ALTER TABLE stock_signal_daily_history
    ADD COLUMN strategy_code VARCHAR(64) NOT NULL DEFAULT 'LEGACY' COMMENT '该历史信号的方案编码' AFTER signal_date,
    ADD COLUMN strategy_version VARCHAR(32) NOT NULL DEFAULT 'legacy' COMMENT '该历史信号的方案版本' AFTER strategy_code,
    ADD COLUMN strategy_name VARCHAR(128) NULL COMMENT '该历史信号生成时冻结的方案名称' AFTER strategy_version,
    ADD KEY idx_stock_signal_history_strategy_pit (strategy_code, strategy_version, signal_date, symbol, available_at);

-- legacy 两个分支与 V7 完全一致，迁移不改写旧内容指纹或追加假历史。
ALTER TABLE stock_signal_daily
    DROP COLUMN signal_content_fingerprint,
    ADD COLUMN signal_content_fingerprint CHAR(64) CHARACTER SET ascii
        GENERATED ALWAYS AS (
            CASE
                WHEN strategy_code = 'LEGACY' AND strategy_version = 'legacy' AND trace_json IS NULL THEN
                    SHA2(CAST(JSON_ARRAY(
                        symbol, signal_date, `signal`, signal_direction, signal_level,
                        bullish_score, bearish_score, risk_score, confidence,
                        triggered_rules, explanation, risk_disclaimer
                    ) AS CHAR), 256)
                WHEN strategy_code = 'LEGACY' AND strategy_version = 'legacy' THEN
                    SHA2(CAST(JSON_ARRAY(
                        symbol, signal_date, `signal`, signal_direction, signal_level,
                        bullish_score, bearish_score, risk_score, confidence,
                        triggered_rules, explanation, risk_disclaimer, trace_json
                    ) AS CHAR), 256)
                ELSE
                    SHA2(CAST(JSON_ARRAY(
                        symbol, signal_date, strategy_code, strategy_version, strategy_name,
                        `signal`, signal_direction, signal_level,
                        bullish_score, bearish_score, risk_score, confidence,
                        triggered_rules, explanation, risk_disclaimer, trace_json
                    ) AS CHAR), 256)
            END
        ) STORED COMMENT '包含方案身份的信号指纹；旧记录保留 V7 指纹';

-- 同一股票的收益可相同，但 hit 等结论必须属于各自的 signal_id。
ALTER TABLE stock_actual_result
    ADD COLUMN signal_id BIGINT NULL COMMENT '对应正式信号 id；无法关联的旧结果为空' AFTER id,
    ADD COLUMN strategy_code VARCHAR(64) NOT NULL DEFAULT 'LEGACY' COMMENT '对应方案编码；旧记录 LEGACY' AFTER signal_date,
    ADD COLUMN strategy_version VARCHAR(32) NOT NULL DEFAULT 'legacy' COMMENT '对应方案版本；旧记录 legacy' AFTER strategy_code;

UPDATE stock_actual_result actual_result
JOIN stock_signal_daily signal_record
  ON signal_record.symbol = actual_result.symbol
 AND signal_record.signal_date = actual_result.signal_date
 AND signal_record.strategy_code = 'LEGACY'
 AND signal_record.strategy_version = 'legacy'
SET actual_result.signal_id = signal_record.id;

ALTER TABLE stock_actual_result
    DROP INDEX uk_stock_actual_result_symbol_signal_date,
    ADD UNIQUE KEY uk_stock_actual_result_signal_id (signal_id),
    ADD UNIQUE KEY uk_stock_actual_result_strategy_identity (symbol, signal_date, strategy_code, strategy_version),
    ADD KEY idx_stock_actual_result_strategy_date (strategy_code, strategy_version, signal_date);
