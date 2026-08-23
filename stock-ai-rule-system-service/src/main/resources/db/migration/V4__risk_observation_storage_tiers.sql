CREATE TABLE risk_indicator_baseline (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(16) NOT NULL COMMENT '1-5d/5-20d/20-60d',
    trade_date DATE NOT NULL,
    dimension_code CHAR(1) NOT NULL COMMENT 'V/T/S/C/A',
    indicator_code VARCHAR(64) NOT NULL,
    component_code VARCHAR(64) NOT NULL,
    actual_value DECIMAL(30,10) NULL COMMENT '评分使用值；无有效值时保持 NULL',
    unit VARCHAR(32) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL COMMENT '当前自然键保留版本的可用时间',
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_risk_baseline_object_component_date (
        object_type, object_id, horizon, trade_date, indicator_code, component_code
    ),
    KEY idx_risk_baseline_object_horizon_date (
        object_type, object_id, horizon, trade_date
    ),
    KEY idx_risk_baseline_date_dimension (trade_date, dimension_code),
    KEY idx_risk_baseline_available_at (available_at),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风险评分紧凑基线；每个交易日分量仅保留最新版本';

CREATE TABLE risk_indicator_observation_archive (
    id BIGINT NOT NULL,
    object_type VARCHAR(16) NOT NULL COMMENT 'market/sector/stock',
    object_id VARCHAR(64) NOT NULL,
    horizon VARCHAR(16) NOT NULL COMMENT '1-5d/5-20d/20-60d',
    trade_date DATE NOT NULL,
    dimension_code CHAR(1) NOT NULL COMMENT 'V/T/S/C/A',
    indicator_code VARCHAR(64) NOT NULL,
    component_code VARCHAR(64) NOT NULL,
    indicator_value DECIMAL(30,10) NULL,
    unit VARCHAR(32) NOT NULL,
    observed_at DATETIME(3) NOT NULL,
    available_at DATETIME(3) NOT NULL,
    source VARCHAR(64) NOT NULL,
    quality_status VARCHAR(32) NOT NULL,
    payload_json JSON NULL,
    created_at DATETIME(3) NOT NULL,
    archived_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_archive_object_code_date_source (
        object_type, object_id, horizon, trade_date, indicator_code,
        component_code, available_at, source
    ),
    KEY idx_risk_archive_object_date (object_type, object_id, trade_date),
    KEY idx_risk_archive_dimension_date (dimension_code, trade_date),
    KEY idx_risk_archive_available_at (available_at),
    CHECK (available_at >= observed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='两年前风险指标完整冷归档';

