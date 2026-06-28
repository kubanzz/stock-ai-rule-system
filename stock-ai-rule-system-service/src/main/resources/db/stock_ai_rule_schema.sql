CREATE TABLE IF NOT EXISTS stock_base (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    market VARCHAR(32),
    exchange VARCHAR(32),
    industry VARCHAR(128),
    status VARCHAR(32),
    data_source VARCHAR(64),
    last_sync_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_base_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基础表';

CREATE TABLE IF NOT EXISTS stock_daily_quote (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    open_price DECIMAL(18,4),
    high_price DECIMAL(18,4),
    low_price DECIMAL(18,4),
    close_price DECIMAL(18,4),
    pre_close DECIMAL(18,4),
    volume DECIMAL(30,4),
    amount DECIMAL(30,4),
    change_pct DECIMAL(10,4),
    data_source VARCHAR(64),
    sync_time DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_stock_daily_quote_symbol_trade_date (symbol, trade_date),
    KEY idx_stock_daily_quote_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行情数据表';

CREATE TABLE IF NOT EXISTS trade_calendar (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    market VARCHAR(32) NOT NULL,
    trade_date DATE NOT NULL,
    is_open TINYINT(1) NOT NULL DEFAULT 0,
    pre_trade_date DATE,
    next_trade_date DATE,
    data_source VARCHAR(64),
    sync_time DATETIME,
    UNIQUE KEY uk_trade_calendar_market_trade_date (market, trade_date),
    KEY idx_trade_calendar_trade_date (trade_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易日历表';

CREATE TABLE IF NOT EXISTS market_data_sync_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    data_source VARCHAR(64),
    sync_type VARCHAR(64),
    status VARCHAR(32),
    request_params JSON,
    target_symbol VARCHAR(32),
    start_date DATE,
    end_date DATE,
    trigger_type VARCHAR(32),
    trigger_by VARCHAR(64),
    scanned INT DEFAULT 0,
    inserted INT DEFAULT 0,
    updated INT DEFAULT 0,
    skipped INT DEFAULT 0,
    failed INT DEFAULT 0,
    error_message TEXT,
    started_at DATETIME,
    finished_at DATETIME,
    duration_ms BIGINT,
    KEY idx_market_data_sync_run_started_at (started_at),
    KEY idx_market_data_sync_run_status (status),
    KEY idx_market_data_sync_run_target_symbol (target_symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='行情同步运行记录表';

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
    current_version_id BIGINT,
    current_version_no VARCHAR(32),
    enabled TINYINT(1) DEFAULT 0,
    priority INT DEFAULT 0,
    created_by VARCHAR(64),
    updated_by VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_definition_rule_code (rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则表';

CREATE TABLE IF NOT EXISTS rule_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    rule_id BIGINT NOT NULL,
    version_no VARCHAR(32) NOT NULL,
    rule_content TEXT,
    change_reason TEXT,
    source VARCHAR(32),
    approval_status VARCHAR(32),
    published_time DATETIME,
    created_by VARCHAR(64),
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rule_version_rule_id_version_no (rule_id, version_no),
    KEY idx_rule_version_rule_id (rule_id),
    KEY idx_rule_version_approval_status (approval_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则版本表';

CREATE TABLE IF NOT EXISTS rule_operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    target_type VARCHAR(32),
    target_id VARCHAR(64),
    operation VARCHAR(64),
    operator VARCHAR(64),
    reason TEXT,
    before_status VARCHAR(32),
    after_status VARCHAR(32),
    created_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_rule_operation_target (target_type, target_id),
    KEY idx_rule_operation_created_time (created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='规则操作审计表';

CREATE TABLE IF NOT EXISTS stock_signal_daily (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(32) NOT NULL,
    signal_date DATE NOT NULL,
    `signal` VARCHAR(32),
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
    KEY idx_stock_signal_daily_signal (`signal`)
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
    source_review_id BIGINT,
    source VARCHAR(32),
    target_rule_code VARCHAR(64),
    change_type VARCHAR(64),
    original_content TEXT,
    proposed_content TEXT,
    reason TEXT,
    status VARCHAR(32),
    backtest_status VARCHAR(32),
    latest_backtest_report_id BIGINT,
    approval_status VARCHAR(32),
    reject_reason TEXT,
    backtest_result JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_candidate_rule_candidate_code (candidate_code),
    KEY idx_candidate_rule_status (status),
    KEY idx_candidate_rule_backtest_status (backtest_status),
    KEY idx_candidate_rule_approval_status (approval_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='候选规则表';

CREATE TABLE IF NOT EXISTS backtest_result (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    object_type VARCHAR(32),
    object_code VARCHAR(64),
    rule_id BIGINT,
    candidate_rule_id BIGINT,
    symbol VARCHAR(32),
    start_date DATE,
    end_date DATE,
    holding_period INT,
    trigger_count INT,
    win_rate DECIMAL(10,4),
    avg_return DECIMAL(10,4),
    max_drawdown DECIMAL(10,4),
    sharpe_ratio DECIMAL(10,4),
    profit_loss_ratio DECIMAL(10,4),
    fee_rate DECIMAL(10,4),
    slippage_rate DECIMAL(10,4),
    total_return DECIMAL(10,4),
    avg_holding_return DECIMAL(10,4),
    status VARCHAR(32),
    result_json JSON,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_backtest_result_object (object_type, object_code),
    KEY idx_backtest_result_period (start_date, end_date),
    KEY idx_backtest_result_rule_id (rule_id),
    KEY idx_backtest_result_candidate_rule_id (candidate_rule_id),
    KEY idx_backtest_result_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测结果表';

CREATE TABLE IF NOT EXISTS workflow_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_date DATE,
    status VARCHAR(32),
    trigger_type VARCHAR(32),
    trigger_by VARCHAR(64),
    dry_run TINYINT(1) DEFAULT 1,
    request_params JSON,
    summary JSON,
    started_at DATETIME,
    finished_at DATETIME,
    error_message TEXT,
    KEY idx_workflow_run_biz_date (biz_date),
    KEY idx_workflow_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日流程运行表';

CREATE TABLE IF NOT EXISTS workflow_step_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workflow_run_id BIGINT NOT NULL,
    step_code VARCHAR(64),
    step_order INT,
    status VARCHAR(32),
    input_params JSON,
    output_summary JSON,
    duration_ms BIGINT,
    started_at DATETIME,
    finished_at DATETIME,
    error_message TEXT,
    KEY idx_workflow_step_run_workflow_run_id (workflow_run_id),
    KEY idx_workflow_step_run_step_code (step_code),
    KEY idx_workflow_step_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='每日流程步骤运行表';
