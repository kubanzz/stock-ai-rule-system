-- 历史补算按实际可用时间入库；已有信号生成时间不可推断，保持 NULL。
ALTER TABLE stock_signal_daily
    ADD COLUMN generation_type VARCHAR(16) NOT NULL DEFAULT 'regular' COMMENT 'regular 或 backfill' AFTER signal_date,
    ADD COLUMN generated_at DATETIME(3) NULL COMMENT '信号实际计算时间，旧记录为空' AFTER generation_type;

CREATE TABLE signal_backfill_run (
    run_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    snapshot_json JSON NOT NULL,
    started_at DATETIME(3) NOT NULL,
    finished_at DATETIME(3) NULL,
    KEY idx_signal_backfill_run_started_at (started_at),
    KEY idx_signal_backfill_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注股票行情和信号补齐任务';
