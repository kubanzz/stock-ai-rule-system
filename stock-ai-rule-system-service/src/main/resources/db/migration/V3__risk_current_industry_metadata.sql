ALTER TABLE risk_object_exposure
    ADD COLUMN parent_object_name VARCHAR(128) NULL
        COMMENT '行业当前展示名称；仅记录真实采集名称，不用于伪造历史归属'
        AFTER parent_object_id;
