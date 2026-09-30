package com.jx.tracker.rule.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.entity.CandidateRule;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.rule.engine.JsonRuleToDroolsCompiler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Retires the three untouched V6 seeds only when the paired V5 candidate still
 * covers the same factor conditions and has been published as a verified DRL.
 * The SQL guards mirror V11 so a publication after that migration has the same
 * behavior without changing an edited or strategy-referenced seed.
 */
@Service
public class RuleSeedDeduplicationService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEDUP_OPERATOR = "rule-publish-dedup";
    private static final List<DuplicatePair> PAIRS = List.of(
            new DuplicatePair(
                    "R_DROOLS_BEARISH_GUARD_001",
                    "CR_TREND_BEAR_GUARD_001",
                    "f7b256f1533265e88b5f9aeff9a7a7a395c1e37bca2cd13fb0d214ed604e0d89",
                    "18758fb9d6be527acfa87f2f600aa0f683a5ca82a0d989843779151cac14f531",
                    """
                    [{"field":"data_status","operator":"eq","value":"normal"},
                     {"field":"short_term_trend","operator":"in","value":["down","strong_down"]},
                     {"field":"technical_status","operator":"eq","value":"bearish"}]
                    """),
            new DuplicatePair(
                    "R_DROOLS_MACD_VOLUME_DIVERGENCE_001",
                    "CR_VOLUME_MACD_DIVERGENCE_001",
                    "8ab5b0d843a475ba24e7a8e422572bc2dc262931a7d388ed752fc5d42179cedd",
                    "4bdf5357c3c14dce849048a67e5fa253d1e2f0f195969540232a5bdea402df99",
                    """
                    [{"field":"data_status","operator":"eq","value":"normal"},
                     {"field":"volume_ratio_5d","operator":"gte","value":1.30},
                     {"field":"macd_histogram","operator":"lt","value":0}]
                    """),
            new DuplicatePair(
                    "R_DROOLS_OVERSOLD_NOTICE_001",
                    "CR_OVERSOLD_RISK_GUARD_001",
                    "3bb1d36e2a371d0c01014df87e63212d918c611ab9fb9ee2747bb43a5bfab51e",
                    "d27016afc6703d0455305024d9de75244b800e635785fe4a27e83f222373ccc4",
                    """
                    [{"field":"data_status","operator":"eq","value":"normal"},
                     {"field":"technical_status","operator":"eq","value":"oversold"},
                     {"field":"rsi14","operator":"lte","value":30}]
                    """)
    );
    private static final Map<String, DuplicatePair> BY_CANDIDATE = PAIRS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(DuplicatePair::candidateCode, pair -> pair));
    private static final Map<String, DuplicatePair> BY_SEED = PAIRS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(DuplicatePair::seedCode, pair -> pair));

    private final JdbcTemplate jdbcTemplate;
    private final JsonRuleToDroolsCompiler compiler;

    public RuleSeedDeduplicationService(JdbcTemplate jdbcTemplate, JsonRuleToDroolsCompiler compiler) {
        this.jdbcTemplate = jdbcTemplate;
        this.compiler = compiler;
    }

    /** Called within the candidate publication transaction. */
    public void disableSupersededSeedRule(CandidateRule candidate, String publishedContent) {
        if (candidate == null) {
            return;
        }
        DuplicatePair pair = BY_CANDIDATE.get(candidate.getCandidateCode());
        if (pair == null
                || !pair.candidateCode().equals(candidate.getTargetRuleCode())
                || !"published".equals(candidate.getStatus())
                || !"approved".equals(candidate.getApprovalStatus())
                || !"success".equals(candidate.getBacktestStatus())) {
            return;
        }
        if (!matchesKnownCandidate(pair, candidate.getProposedContent())
                || !matchesPublishedContent(pair, candidate.getProposedContent(), publishedContent)) {
            assertSeedInactiveForUnverifiedReplacement(pair);
            return;
        }

        int changed = jdbcTemplate.update("""
                UPDATE rule_definition seed
                JOIN rule_definition published ON published.rule_code = ?
                JOIN candidate_rule candidate ON candidate.candidate_code = ?
                    AND candidate.target_rule_code = published.rule_code
                SET seed.status = 'disabled', seed.enabled = 0, seed.updated_by = ?
                WHERE seed.rule_code = ?
                  AND seed.status = 'active' AND seed.enabled = 1
                  AND seed.rule_format = 'drools' AND seed.version = 'v1'
                  AND seed.current_version_id IS NULL
                  AND seed.created_by = 'system-seed' AND seed.updated_by = 'system-seed'
                  AND SHA2(seed.rule_content, 256) = ?
                  AND NOT EXISTS (SELECT 1 FROM rule_version version WHERE version.rule_id = seed.id)
                  AND published.status = 'active' AND published.enabled = 1
                  AND published.rule_format = 'drools'
                  AND SHA2(published.rule_content, 256) = ?
                  AND candidate.status = 'published'
                  AND candidate.approval_status = 'approved'
                  AND candidate.backtest_status = 'success'
                  AND SHA2(candidate.proposed_content, 256) = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM rule_strategy strategy
                      WHERE strategy.status = 'active'
                        AND JSON_SEARCH(strategy.snapshot_json, 'one', seed.rule_code) IS NOT NULL
                  )
                """, pair.candidateCode(), pair.candidateCode(), DEDUP_OPERATOR,
                pair.seedCode(), pair.seedHash(), sha256(publishedContent), sha256(candidate.getProposedContent()));
        if (changed == 1) {
            jdbcTemplate.update("""
                    INSERT INTO rule_operation_log (
                        target_type, target_id, operation, operator, reason, before_status, after_status
                    ) VALUES ('rule', ?, 'disable_duplicate', ?, ?, 'active', 'disabled')
                    """, pair.seedCode(), DEDUP_OPERATOR,
                    "已发布规则 " + pair.candidateCode() + " 覆盖相同因子条件，停用未经编辑的基础种子以避免重复计分；保留规则与历史。");
        } else {
            assertSeedInactiveForUnverifiedReplacement(pair);
        }
    }

    /** Prevents re-enabling a known paired seed while its replacement is active. */
    private void assertNoActivePublishedCounterpart(String seedCode) {
        DuplicatePair pair = BY_SEED.get(seedCode);
        if (pair == null) {
            return;
        }
        if (isActiveRule(pair.candidateCode())) {
            throw new ServiceException("基础规则 " + seedCode + " 与正式规则 " + pair.candidateCode()
                    + " 可能覆盖相同因子条件，不能重复启用");
        }
    }

    /** Guards both sides when governance edits or re-enables a rule directly. */
    public void assertNoActiveDuplicate(String ruleCode, String ruleContent) {
        if (BY_SEED.containsKey(ruleCode)) {
            assertNoActivePublishedCounterpart(ruleCode);
            return;
        }
        DuplicatePair pair = BY_CANDIDATE.get(ruleCode);
        if (pair == null) {
            return;
        }
        if (isActiveRule(pair.seedCode())) {
            throw new ServiceException("正式规则 " + ruleCode + " 与仍启用的基础规则 " + pair.seedCode()
                    + " 可能覆盖相同因子条件，不能重复启用");
        }
    }

    private void assertSeedInactiveForUnverifiedReplacement(DuplicatePair pair) {
        if (isActiveRule(pair.seedCode())) {
            throw new ServiceException("候选规则 " + pair.candidateCode() + " 与仍启用的基础规则 "
                    + pair.seedCode() + " 的条件覆盖关系需要人工复核，请先停用基础规则或调整应用方案");
        }
    }

    private boolean isActiveRule(String ruleCode) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM rule_definition
                WHERE rule_code = ? AND status = 'active' AND enabled = 1
                  AND rule_format = 'drools'
                """, Integer.class, ruleCode);
        return count != null && count > 0;
    }

    private boolean matchesPublishedContent(DuplicatePair pair, String candidateContent, String publishedContent) {
        if (publishedContent == null) {
            return false;
        }
        if (pair.legacyPublishedHash().equals(sha256(publishedContent))) {
            return true;
        }
        try {
            return publishedContent.equals(compiler.compile(pair.candidateCode(), candidateContent));
        } catch (ServiceException invalidCandidate) {
            return false;
        }
    }

    private static boolean matchesKnownCandidate(DuplicatePair pair, String content) {
        if (content == null) {
            return false;
        }
        try {
            JsonNode candidate = OBJECT_MAPPER.readTree(content);
            JsonNode conditions = candidate.path("conditions");
            JsonNode expected = OBJECT_MAPPER.readTree(pair.conditions());
            if (!conditions.isArray() || conditions.size() != expected.size()) {
                return false;
            }
            List<String> actualPredicates = new ArrayList<>();
            List<String> expectedPredicates = new ArrayList<>();
            for (JsonNode condition : conditions) {
                actualPredicates.add(normalizePredicate(condition));
            }
            for (JsonNode condition : expected) {
                expectedPredicates.add(normalizePredicate(condition));
            }
            actualPredicates.sort(String::compareTo);
            expectedPredicates.sort(String::compareTo);
            return actualPredicates.equals(expectedPredicates);
        } catch (Exception invalidCandidate) {
            return false;
        }
    }

    private static String normalizePredicate(JsonNode condition) {
        if (!condition.isObject()
                || !condition.path("field").isTextual()
                || !condition.path("operator").isTextual()) {
            throw new IllegalArgumentException("条件格式不正确");
        }
        String field = condition.path("field").asText();
        String operator = condition.path("operator").asText();
        JsonNode value = condition.path("value");
        if ("in".equals(operator)) {
            if (!value.isArray()) {
                throw new IllegalArgumentException("in 条件缺少数组值");
            }
            List<String> choices = new ArrayList<>();
            for (JsonNode item : value) {
                if (!item.isValueNode()) {
                    throw new IllegalArgumentException("in 条件包含非标量值");
                }
                choices.add(item.asText());
            }
            choices.sort(String::compareTo);
            return field + ":" + operator + ":" + choices;
        }
        if (!value.isValueNode()) {
            throw new IllegalArgumentException("条件缺少标量值");
        }
        return field + ":" + operator + ":" + value.asText();
    }

    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private record DuplicatePair(String seedCode, String candidateCode, String seedHash,
                                 String legacyPublishedHash, String conditions) {
    }

}
