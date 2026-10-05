package com.jx.tracker.rule;

import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.rule.engine.DroolsRuleEngineExecutor;
import com.jx.tracker.rule.engine.RuleExecutionRequest;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.signal.service.StrategyExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class P4VoteRulesTest {
    private static final List<String> CODES = List.of("R_P4_VOTE_001_CHANGE_PCT_5D", "R_P4_VOTE_001_OPEN_GAP", "R_P4_VOTE_001_INDEX_CLOSE_POSITION");
    private static final String P = "p4_vote_001_";
    private final DroolsRuleEngineExecutor executor = new DroolsRuleEngineExecutor();

    @Test
    void exactlyTwoOfThreeAcrossAllEightCombinations() throws Exception {
        List<RuleDefinition> rules = rules();
        RuleStrategyDetailDto strategy = strategy();
        for (int bits = 0; bits < 8; bits++) {
            int matched = Integer.bitCount(bits);
            Map<String, Object> factors = base();
            factors.put(P + "change_pct_5d", (bits & 1) != 0 ? -.10001 : -.09999);
            factors.put(P + "open_gap", (bits & 2) != 0 ? .02001 : .01999);
            factors.put(P + "index_close_position", (bits & 4) != 0 ? .19999 : .20001);
            var raw = executor.execute(new RuleExecutionRequest("600418.SH", LocalDate.of(2026, 9, 30), factors, rules));
            var result = new StrategyExecutionService().aggregate(strategy, raw);
            assertThat(raw.triggeredRules()).hasSize(matched);
            assertThat(result.result().bullishScore()).isEqualByComparingTo(matched >= 2 ? Integer.toString(matched * 30) : "0");
            assertThat(result.trace().requiredGroupGatePassed()).isEqualTo(matched >= 2);
            assertThat(new SignalScoringService().score(result.result().bullishScore(), result.result().bearishScore(),
                    result.result().riskScore(), strategy).signal()).isEqualTo(matched >= 2 ? "bullish" : "watch");
        }
    }

    @Test
    void unavailableIndexStillAllowsTwoStockAtomsAndEligibilityBlocksAll() throws Exception {
        Map<String, Object> factors = base();
        factors.put(P + "change_pct_5d", -.11);
        factors.put(P + "open_gap", .03);
        var raw = executor.execute(new RuleExecutionRequest("600418.SH", LocalDate.of(2026, 9, 30), factors, rules()));
        assertThat(new StrategyExecutionService().aggregate(strategy(), raw).result().bullishScore()).isEqualByComparingTo("60");
        factors.put(P + "eligible", false);
        assertThat(executor.execute(new RuleExecutionRequest("600418.SH", LocalDate.of(2026, 9, 30), factors, rules())).triggeredRules()).isEmpty();
        factors.put(P + "eligible", true);factors.put(P + "version", "other");
        assertThat(executor.execute(new RuleExecutionRequest("600418.SH", LocalDate.of(2026, 9, 30), factors, rules())).triggeredRules()).isEmpty();
    }

    private static Map<String, Object> base() {
        Map<String, Object> factors = new HashMap<>();
        factors.put(P + "version", "p4-vote-001-v1");
        factors.put(P + "eligible", true);
        return factors;
    }
    private static List<RuleDefinition> rules() throws Exception {
        List<RuleDefinition> result = new ArrayList<>();
        for (String code : CODES) result.add(RuleDefinition.builder().ruleCode(code).ruleName(code).ruleFormat("drools")
                .ruleContent(new ClassPathResource("rules/" + code + ".drl").getContentAsString(StandardCharsets.UTF_8))
                .version("v1").status("active").enabled(true).priority(120).build());
        return result;
    }
    private static RuleStrategyDetailDto strategy() {
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode("P4-VOTE-001");group.setVersion("v1");group.setAggregation("WEIGHTED");group.setMinMatchedRules(2);
        List<RuleGroupMemberDto> members = new ArrayList<>();
        for (String code : CODES) {
            RuleGroupMemberDto member = new RuleGroupMemberDto();member.setRuleCode(code);member.setWeight(BigDecimal.ONE);member.setRequired(false);members.add(member);
        }
        group.setMembers(members);
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode(group.getGroupCode());selected.setGroup(group);selected.setWeight(BigDecimal.ONE);selected.setRequired(true);
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        strategy.setStrategyCode("P4-VOTE-001");strategy.setVersion("v1");strategy.setGroups(List.of(selected));
        strategy.setBullishThreshold(new BigDecimal("60"));strategy.setBearishThreshold(new BigDecimal("60"));strategy.setRiskThreshold(new BigDecimal("80"));
        return strategy;
    }
}
