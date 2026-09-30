CREATE TABLE rule_group (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_code VARCHAR(64) NOT NULL,
    group_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    aggregation VARCHAR(16) NOT NULL,
    min_matched_rules INT NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_group_code (group_code),
    KEY idx_rule_group_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则组当前配置';

CREATE TABLE rule_group_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    group_id BIGINT NOT NULL,
    version_no VARCHAR(32) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_group_version (group_id, version_no),
    KEY idx_rule_group_version_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则组不可变配置快照';

CREATE TABLE rule_strategy (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_code VARCHAR(64) NOT NULL,
    strategy_name VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    bullish_threshold DECIMAL(10,4) NOT NULL,
    bearish_threshold DECIMAL(10,4) NOT NULL,
    risk_threshold DECIMAL(10,4) NOT NULL,
    snapshot_json JSON NOT NULL,
    -- MySQL UNIQUE permits multiple NULL values; only one strategy may be active.
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'active' THEN 1 ELSE NULL END) STORED,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_strategy_code (strategy_code),
    UNIQUE KEY uk_rule_strategy_active_slot (active_slot),
    KEY idx_rule_strategy_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用方案当前配置';

CREATE TABLE rule_strategy_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    strategy_id BIGINT NOT NULL,
    version_no VARCHAR(32) NOT NULL,
    snapshot_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_strategy_version (strategy_id, version_no),
    KEY idx_rule_strategy_version_strategy (strategy_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用方案不可变配置快照';
