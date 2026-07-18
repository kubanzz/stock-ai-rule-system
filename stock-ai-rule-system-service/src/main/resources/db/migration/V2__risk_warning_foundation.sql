ALTER TABLE stock_signal_daily
    ADD COLUMN signal_direction VARCHAR(16) NULL COMMENT '信号方向：bullish/bearish/watch' AFTER `signal`;

UPDATE stock_signal_daily
SET signal_direction = CASE
    WHEN `signal` = 'high_risk' THEN CASE
        WHEN bullish_score > bearish_score THEN 'bullish'
        WHEN bearish_score > bullish_score THEN 'bearish'
        ELSE 'watch'
    END
    WHEN `signal` = 'bullish' THEN 'bullish'
    WHEN `signal` = 'bearish' THEN 'bearish'
    WHEN `signal` = 'watch' THEN 'watch'
    WHEN bullish_score > bearish_score THEN 'bullish'
    WHEN bearish_score > bullish_score THEN 'bearish'
    ELSE 'watch'
END
WHERE signal_direction IS NULL;

ALTER TABLE stock_signal_daily
    MODIFY COLUMN signal_direction VARCHAR(16) NOT NULL DEFAULT 'watch' COMMENT '信号方向：bullish/bearish/watch',
    ADD KEY idx_stock_signal_daily_direction_date (signal_direction, signal_date);

CREATE TABLE risk_object_exposure (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    parent_object_type VARCHAR(16) NOT NULL COMMENT 'market/sector',
    parent_object_id VARCHAR(64) NOT NULL,
    exposure_weight DECIMAL(8,6) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_object_exposure_object_parent_period (
        object_type, object_id, parent_object_type, parent_object_id, valid_from, source
    ),
    KEY idx_risk_object_exposure_parent_period (parent_object_type, parent_object_id, valid_from, valid_to),
    KEY idx_risk_object_exposure_object_period (object_type, object_id, valid_from, valid_to),
    CHECK (exposure_weight >= 0 AND exposure_weight <= 1),
    CHECK (available_at >= observed_at),
    CHECK (valid_to IS NULL OR valid_to >= valid_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险对象层级与行业有效期暴露';

CREATE TABLE risk_indicator_observation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(16) NOT NULL COMMENT '1-5d/5-20d/20-60d',
    trade_date DATE NOT NULL,
    dimension_code CHAR(1) NOT NULL COMMENT 'V/T/S/C/A',
    indicator_code VARCHAR(64) NOT NULL,
    indicator_value DECIMAL(30,10) NULL COMMENT '不可用时保持 NULL，不以 0 代替',
    unit VARCHAR(32) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    payload_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_indicator_object_code_date_source (
        object_type, object_id, horizon, trade_date, indicator_code, source
    ),
    KEY idx_risk_indicator_object_date (object_type, object_id, trade_date),
    KEY idx_risk_indicator_dimension_date (dimension_code, trade_date),
    KEY idx_risk_indicator_available_at (available_at),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险指标原始观测';

CREATE TABLE risk_event_fact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    trade_date DATE NOT NULL,
    dimension_code CHAR(1) NOT NULL COMMENT 'V/T/S/C/A',
    event_type VARCHAR(64) NOT NULL,
    event_key VARCHAR(128) NOT NULL,
    severity_score DECIMAL(7,4) NULL COMMENT '0-100 综合严重度分数，不代表概率',
    occurred_at DATETIME(3) NOT NULL COMMENT '事件实际或计划生效时间，可晚于首次观测时间',
    observed_at DATETIME(3) NOT NULL COMMENT '数据源首次观测时间',
    available_at DATETIME(3) NOT NULL COMMENT '系统可用时间',
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    event_payload JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_event_source_key (source, event_type, event_key, object_type, object_id),
    KEY idx_risk_event_object_date (object_type, object_id, trade_date),
    KEY idx_risk_event_type_date (event_type, trade_date),
    KEY idx_risk_event_available_at (available_at),
    CHECK (severity_score IS NULL OR (severity_score >= 0 AND severity_score <= 100)),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险事件事实';

CREATE TABLE risk_score_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(16) NOT NULL COMMENT '1-5d/5-20d/20-60d',
    trade_date DATE NOT NULL,
    v_score DECIMAL(7,4) NULL,
    t_score DECIMAL(7,4) NULL,
    s_score DECIMAL(7,4) NULL,
    c_score DECIMAL(7,4) NULL,
    a_score DECIMAL(7,4) NULL,
    m_score DECIMAL(7,4) NULL COMMENT '0.90-1.20 时间窗口修正系数',
    total_score DECIMAL(7,4) NULL COMMENT '0-100 综合风险分，不代表概率',
    risk_level VARCHAR(16) NULL COMMENT 'normal/watch/warning/critical',
    risk_stage VARCHAR(16) NULL COMMENT 'fragile/repricing/stampede/easing',
    completeness DECIMAL(6,5) NOT NULL COMMENT '0-1 数据完整度',
    risk_confidence DECIMAL(6,5) NULL COMMENT '0-1 风险结论置信度',
    model_version VARCHAR(64) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'risk-engine',
    quality_status VARCHAR(32) NOT NULL,
    calculated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_score_snapshot_object_horizon_date_model (
        object_type, object_id, horizon, trade_date, model_version
    ),
    KEY idx_risk_score_snapshot_date_level (trade_date, risk_level),
    KEY idx_risk_score_snapshot_object_date (object_type, object_id, trade_date),
    KEY idx_risk_score_snapshot_calculated_at (calculated_at),
    CHECK (v_score IS NULL OR (v_score >= 0 AND v_score <= 100)),
    CHECK (t_score IS NULL OR (t_score >= 0 AND t_score <= 100)),
    CHECK (s_score IS NULL OR (s_score >= 0 AND s_score <= 100)),
    CHECK (c_score IS NULL OR (c_score >= 0 AND c_score <= 100)),
    CHECK (a_score IS NULL OR (a_score >= 0 AND a_score <= 100)),
    CHECK (m_score IS NULL OR (m_score >= 0.90 AND m_score <= 1.20)),
    CHECK (total_score IS NULL OR (total_score >= 0 AND total_score <= 100)),
    CHECK (completeness >= 0 AND completeness <= 1),
    CHECK (risk_confidence IS NULL OR (risk_confidence >= 0 AND risk_confidence <= 1)),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评分幂等快照';

CREATE TABLE risk_score_evidence (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    dimension_code CHAR(1) NOT NULL COMMENT 'V/T/S/C/A',
    indicator_code VARCHAR(64) NOT NULL,
    raw_value DECIMAL(30,10) NULL,
    indicator_score DECIMAL(7,4) NULL COMMENT '0-100 指标分数',
    weighted_contribution DECIMAL(9,6) NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    evidence_json JSON NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_score_evidence_snapshot_indicator_source (snapshot_id, indicator_code, source),
    KEY idx_risk_score_evidence_snapshot_dimension (snapshot_id, dimension_code),
    KEY idx_risk_score_evidence_available_at (available_at),
    CONSTRAINT fk_risk_score_evidence_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES risk_score_snapshot(id) ON DELETE CASCADE,
    CHECK (indicator_score IS NULL OR (indicator_score >= 0 AND indicator_score <= 100)),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险快照证据';

CREATE TABLE risk_gate_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    snapshot_id BIGINT NOT NULL,
    signal_reference VARCHAR(64) NOT NULL COMMENT '外部信号稳定引用',
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(16) NOT NULL COMMENT '1-5d/5-20d/20-60d',
    trade_date DATE NOT NULL,
    signal_direction VARCHAR(16) NOT NULL COMMENT 'bullish/bearish/watch',
    original_confidence DECIMAL(6,5) NOT NULL,
    suggested_confidence DECIMAL(6,5) NOT NULL,
    suggested_action VARCHAR(16) NULL COMMENT 'normal/notice/downgrade/block',
    enforced TINYINT(1) NOT NULL DEFAULT 0 COMMENT '首轮必须为影子模式',
    reason VARCHAR(512) NOT NULL,
    evidence_json JSON NULL,
    model_version VARCHAR(64) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'risk-gate',
    quality_status VARCHAR(32) NOT NULL,
    calculated_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_gate_signal_horizon_model (signal_reference, horizon, model_version),
    KEY idx_risk_gate_object_date (object_type, object_id, trade_date),
    KEY idx_risk_gate_date_action (trade_date, suggested_action),
    KEY idx_risk_gate_available_at (available_at),
    CONSTRAINT fk_risk_gate_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES risk_score_snapshot(id),
    CHECK (original_confidence >= 0 AND original_confidence <= 1),
    CHECK (suggested_confidence >= 0 AND suggested_confidence <= 1),
    CHECK (available_at >= observed_at),
    CHECK (enforced = 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险影子闸门建议结果';

CREATE TABLE risk_ingestion_checkpoint (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    provider_code VARCHAR(64) NOT NULL,
    dataset_code VARCHAR(64) NOT NULL,
    scope_key VARCHAR(128) NOT NULL,
    checkpoint_value JSON NOT NULL,
    checkpoint_at DATETIME(3) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    last_error VARCHAR(1024) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_checkpoint_provider_dataset_scope (provider_code, dataset_code, scope_key),
    KEY idx_risk_checkpoint_checkpoint_at (checkpoint_at),
    KEY idx_risk_checkpoint_quality (quality_status),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险采集断点';
