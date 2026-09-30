-- 将候选规则与生产规则目录分离。
--
-- V5 曾把 AI 候选同时写入 candidate_rule 和 rule_definition，导致规则治理
-- 页面把尚未审核的候选占位显示为生产规则。候选的 JSON DSL 只应保留在
-- candidate_rule.proposed_content；正式规则在人工发布时由服务创建并编译为
-- Drools。这里只清理 V5 生成的安全可识别占位，不触碰用户编辑、已发布或
-- 已产生版本的规则定义。
DELETE rd
FROM rule_definition rd
INNER JOIN candidate_rule cr
        ON cr.candidate_code = rd.rule_code
       AND cr.target_rule_code = rd.rule_code
WHERE rd.rule_code IN (
          'CR_TREND_BEAR_GUARD_001',
          'CR_OVERSOLD_RISK_GUARD_001',
          'CR_SIDEWAYS_MACD_RECOVERY_001',
          'CR_VOLUME_MACD_DIVERGENCE_001',
          'CR_WEAK_VOLUME_CONTINUATION_001'
      )
  AND rd.created_by = 'historical-review'
  AND rd.status = 'draft'
  AND rd.enabled = 0
  AND rd.rule_format = 'drools'
  AND cr.status NOT IN ('published', 'active')
  AND NOT EXISTS (
      SELECT 1
      FROM rule_version rv
      WHERE rv.rule_id = rd.id
  );
