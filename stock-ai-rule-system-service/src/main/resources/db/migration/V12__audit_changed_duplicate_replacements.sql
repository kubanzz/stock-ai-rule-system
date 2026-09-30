-- V11 按规则编号停用了三条基础种子，没有校验替代规则的实际 DRL。
-- 即使替代规则的内容改变，其条件仍可能与基础种子部分重叠，自动恢复
-- 会造成重复计分。因此仅为仍在生产执行、内容偏离 V8 模板的替代规则
-- 留下待人工复核的审计记录，不改变任一规则的执行状态。
INSERT INTO rule_operation_log (
    target_type, target_id, operation, operator, reason, before_status, after_status
)
SELECT 'rule', seed.rule_code, 'review_changed_replacement', 'system-migration',
       CONCAT('V12: 基础种子仍停用；替代规则 ', replacement.published_code,
              ' 的生产 DRL 与已审核模板不同，需人工复核覆盖范围，避免重复计分。'),
       'disabled', 'disabled'
FROM rule_definition seed
JOIN (
    SELECT 'R_DROOLS_BEARISH_GUARD_001' AS seed_code,
           'CR_TREND_BEAR_GUARD_001' AS published_code,
           'f7b256f1533265e88b5f9aeff9a7a7a395c1e37bca2cd13fb0d214ed604e0d89' AS seed_sha256,
           '18758fb9d6be527acfa87f2f600aa0f683a5ca82a0d989843779151cac14f531' AS published_sha256
    UNION ALL
    SELECT 'R_DROOLS_MACD_VOLUME_DIVERGENCE_001',
           'CR_VOLUME_MACD_DIVERGENCE_001',
           '8ab5b0d843a475ba24e7a8e422572bc2dc262931a7d388ed752fc5d42179cedd',
           '4bdf5357c3c14dce849048a67e5fa253d1e2f0f195969540232a5bdea402df99'
    UNION ALL
    SELECT 'R_DROOLS_OVERSOLD_NOTICE_001',
           'CR_OVERSOLD_RISK_GUARD_001',
           '3bb1d36e2a371d0c01014df87e63212d918c611ab9fb9ee2747bb43a5bfab51e',
           'd27016afc6703d0455305024d9de75244b800e635785fe4a27e83f222373ccc4'
) replacement ON replacement.seed_code = seed.rule_code
JOIN rule_definition published ON published.rule_code = replacement.published_code
JOIN candidate_rule candidate ON candidate.candidate_code = replacement.published_code
    AND candidate.target_rule_code = replacement.published_code
WHERE seed.status = 'disabled'
  AND seed.enabled = 0
  AND seed.rule_format = 'drools'
  AND seed.version = 'v1'
  AND seed.current_version_id IS NULL
  AND seed.created_by = 'system-seed'
  AND seed.updated_by = 'rule-dedup-v11'
  AND SHA2(seed.rule_content, 256) = replacement.seed_sha256
  AND NOT EXISTS (SELECT 1 FROM rule_version version WHERE version.rule_id = seed.id)
  AND EXISTS (
      SELECT 1 FROM rule_operation_log log
      WHERE log.target_type = 'rule'
        AND log.target_id = seed.rule_code
        AND log.operation = 'disable_duplicate'
        AND log.operator = 'system-migration'
  )
  AND published.status = 'active'
  AND published.enabled = 1
  AND published.rule_format = 'drools'
  AND candidate.status = 'published'
  AND candidate.approval_status = 'approved'
  AND candidate.backtest_status = 'success'
  AND COALESCE(SHA2(published.rule_content, 256), '') <> replacement.published_sha256
  AND NOT EXISTS (
      SELECT 1 FROM rule_operation_log log
      WHERE log.target_type = 'rule'
        AND log.target_id = seed.rule_code
        AND log.operation = 'review_changed_replacement'
        AND log.operator = 'system-migration'
  );
