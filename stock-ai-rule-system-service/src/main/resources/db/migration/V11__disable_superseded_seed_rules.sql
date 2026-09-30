-- V6 的三条系统基础规则与已人工发布的 V5 候选规则覆盖相同因子条件。
-- 保留两边的规则定义及历史，只在对应候选已经审核发布且仍启用时，
-- 停用未经编辑、没有独立版本的 V6 原始种子，避免生产信号重复计分。
-- 内容指纹及原始元数据共同限制更新范围；用户修改过的规则不会被迁移覆盖。
UPDATE rule_definition seed
JOIN (
    SELECT 'R_DROOLS_BEARISH_GUARD_001' AS seed_code,
           'CR_TREND_BEAR_GUARD_001' AS published_code,
           'f7b256f1533265e88b5f9aeff9a7a7a395c1e37bca2cd13fb0d214ed604e0d89' AS content_sha256
    UNION ALL
    SELECT 'R_DROOLS_MACD_VOLUME_DIVERGENCE_001',
           'CR_VOLUME_MACD_DIVERGENCE_001',
           '8ab5b0d843a475ba24e7a8e422572bc2dc262931a7d388ed752fc5d42179cedd'
    UNION ALL
    SELECT 'R_DROOLS_OVERSOLD_NOTICE_001',
           'CR_OVERSOLD_RISK_GUARD_001',
           '3bb1d36e2a371d0c01014df87e63212d918c611ab9fb9ee2747bb43a5bfab51e'
) duplicate_pair ON duplicate_pair.seed_code = seed.rule_code
JOIN rule_definition published ON published.rule_code = duplicate_pair.published_code
JOIN candidate_rule candidate ON candidate.candidate_code = duplicate_pair.published_code
    AND candidate.target_rule_code = published.rule_code
SET seed.status = 'disabled',
    seed.enabled = 0,
    seed.updated_by = 'rule-dedup-v11'
WHERE seed.status = 'active'
  AND seed.enabled = 1
  AND seed.rule_format = 'drools'
  AND seed.version = 'v1'
  AND seed.current_version_id IS NULL
  AND seed.created_by = 'system-seed'
  AND seed.updated_by = 'system-seed'
  AND SHA2(seed.rule_content, 256) = duplicate_pair.content_sha256
  AND NOT EXISTS (SELECT 1 FROM rule_version version WHERE version.rule_id = seed.id)
  AND published.status = 'active'
  AND published.enabled = 1
  AND published.rule_format = 'drools'
  AND candidate.status = 'published'
  AND candidate.approval_status = 'approved'
  AND candidate.backtest_status = 'success'
  AND NOT EXISTS (
      SELECT 1
      FROM rule_strategy strategy
      WHERE strategy.status = 'active'
        AND JSON_SEARCH(strategy.snapshot_json, 'one', seed.rule_code) IS NOT NULL
  );

INSERT INTO rule_operation_log (
    target_type, target_id, operation, operator, reason, before_status, after_status
)
SELECT 'rule', seed.rule_code, 'disable_duplicate', 'system-migration',
       CONCAT('V11: 已发布规则 ', duplicate_pair.published_code,
              ' 覆盖相同因子条件，停用未经编辑的基础种子以避免重复计分；保留规则与历史。'),
       'active', 'disabled'
FROM rule_definition seed
JOIN (
    SELECT 'R_DROOLS_BEARISH_GUARD_001' AS seed_code, 'CR_TREND_BEAR_GUARD_001' AS published_code
    UNION ALL
    SELECT 'R_DROOLS_MACD_VOLUME_DIVERGENCE_001', 'CR_VOLUME_MACD_DIVERGENCE_001'
    UNION ALL
    SELECT 'R_DROOLS_OVERSOLD_NOTICE_001', 'CR_OVERSOLD_RISK_GUARD_001'
) duplicate_pair ON duplicate_pair.seed_code = seed.rule_code
WHERE seed.updated_by = 'rule-dedup-v11'
  AND seed.status = 'disabled'
  AND seed.enabled = 0
  AND NOT EXISTS (
      SELECT 1
      FROM rule_operation_log log
      WHERE log.target_type = 'rule'
        AND log.target_id = seed.rule_code
        AND log.operation = 'disable_duplicate'
        AND log.operator = 'system-migration'
  );
