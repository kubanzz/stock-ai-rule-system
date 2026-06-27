CREATE TABLE IF NOT EXISTS stock_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    market VARCHAR(32),
    industry VARCHAR(128),
    status VARCHAR(32),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_base_symbol_market (symbol, market)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基础表';

CREATE TABLE IF NOT EXISTS stock_daily_quote (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    open_price DECIMAL(18,4),
    high_price DECIMAL(18,4),
    low_price DECIMAL(18,4),
    close_price DECIMAL(18,4),
    volume DECIMAL(30,4),
    amount DECIMAL(30,4),
    change_pct DECIMAL(10,4),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_daily_quote_symbol_trade_date (symbol, trade_date),
    KEY idx_stock_daily_quote_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行情数据表';

CREATE TABLE IF NOT EXISTS stock_factor_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    factor_json JSON NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_factor_daily_symbol_trade_date (symbol, trade_date),
    KEY idx_stock_factor_daily_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='因子表';

CREATE TABLE IF NOT EXISTS rule_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_code VARCHAR(64) NOT NULL,
    rule_name VARCHAR(128),
    rule_type VARCHAR(64),
    rule_content TEXT,
    rule_format VARCHAR(32),
    version VARCHAR(32),
    status VARCHAR(32),
    priority INT DEFAULT 0,
    created_by VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_definition_rule_code (rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则表';

CREATE TABLE IF NOT EXISTS stock_signal_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    signal_date DATE NOT NULL,
    signal VARCHAR(32),
    signal_level VARCHAR(32),
    bullish_score DECIMAL(10,4),
    bearish_score DECIMAL(10,4),
    risk_score DECIMAL(10,4),
    confidence DECIMAL(10,4),
    triggered_rules JSON,
    explanation TEXT,
    risk_disclaimer VARCHAR(512),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_signal_daily_symbol_signal_date (symbol, signal_date),
    KEY idx_stock_signal_daily_signal_date (signal_date),
    KEY idx_stock_signal_daily_signal (signal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='信号结果表';

CREATE TABLE IF NOT EXISTS stock_actual_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    signal_date DATE NOT NULL,
    return_1d DECIMAL(10,4),
    return_3d DECIMAL(10,4),
    return_5d DECIMAL(10,4),
    return_10d DECIMAL(10,4),
    hit_1d TINYINT(1),
    hit_5d TINYINT(1),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_actual_result_symbol_signal_date (symbol, signal_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实际表现表';

CREATE TABLE IF NOT EXISTS ai_review_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32),
    review_date DATE,
    signal_id BIGINT,
    diagnosis TEXT,
    suggestions JSON,
    model_name VARCHAR(64),
    risk_disclaimer VARCHAR(512),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ai_review_report_review_date (review_date),
    KEY idx_ai_review_report_signal_id (signal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 复盘表';

CREATE TABLE IF NOT EXISTS candidate_rule (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    candidate_code VARCHAR(64),
    source VARCHAR(32),
    target_rule_code VARCHAR(64),
    change_type VARCHAR(64),
    original_content TEXT,
    proposed_content TEXT,
    reason TEXT,
    status VARCHAR(32),
    backtest_result JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_candidate_rule_candidate_code (candidate_code),
    KEY idx_candidate_rule_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选规则表';

CREATE TABLE IF NOT EXISTS backtest_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(32),
    object_code VARCHAR(64),
    start_date DATE,
    end_date DATE,
    trigger_count INT,
    win_rate DECIMAL(10,4),
    avg_return DECIMAL(10,4),
    max_drawdown DECIMAL(10,4),
    sharpe_ratio DECIMAL(10,4),
    result_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_backtest_result_object (object_type, object_code),
    KEY idx_backtest_result_period (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测结果表';
