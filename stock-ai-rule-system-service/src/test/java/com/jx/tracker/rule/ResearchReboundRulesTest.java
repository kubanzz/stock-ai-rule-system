package com.jx.tracker.rule;

import com.jx.tracker.domain.dto.*;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.rule.engine.*;
import com.jx.tracker.signal.service.StrategyExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class ResearchReboundRulesTest {
    private static final List<String> CODES = List.of("R_G144_A1_MARKET_LOW", "R_G118_A1_MARKET_MA60", "R_G144_G118_A2_MARKET_MA20");
    private final DroolsRuleEngineExecutor executor = new DroolsRuleEngineExecutor();

    @Test void sharedA2MakesBothGroupsEligibleButContributesOnlyOnce() throws Exception {
        Map<String,Object> factors = base();
        factors.putAll(Map.of("research_volatility_20d",.019,"research_return_1d",-.031,
                "research_intraday_return",-.031,"research_volume_ratio_5d",1,
                "research_hs300_distance_ma20",-.01));
        var raw = run(factors);
        var result = new StrategyExecutionService().aggregate(strategy(), raw);
        assertThat(raw.triggeredRules()).containsExactly(CODES.get(2));
        assertThat(result.result().bullishScore()).isEqualByComparingTo("55");
        assertThat(result.trace().groupContributions()).allMatch(g -> g.eligible());
    }

    @Test void simultaneousA1ConfirmationsDoNotInflateTheScore() throws Exception {
        Map<String,Object> factors = a1();
        var raw = run(factors);
        var result = new StrategyExecutionService().aggregate(strategy(), raw);
        assertThat(raw.triggeredRules()).containsExactly(CODES.get(0), CODES.get(1));
        assertThat(result.result().bullishScore()).isEqualByComparingTo("55");
        assertThat(result.trace().groupContributions()).allMatch(g -> g.eligible());
        assertThat(raw.ruleEvaluations().stream().filter(e -> e.status().equals("MATCHED")).count()).isEqualTo(2);
    }

    @Test void missingIndexOrFailedEligibilityProducesNoSignal() throws Exception {
        Map<String,Object> factors = a1();
        factors.put("research_rebound_eligible",false);
        assertThat(run(factors).triggeredRules()).isEmpty();
        factors.put("research_rebound_eligible",true);
        factors.remove("research_hs300_close_position");
        factors.remove("research_hs300_distance_ma60");
        assertThat(run(factors).triggeredRules()).isEmpty();
    }

    @Test void marketAndUnroundedThresholdsKeepTheTwoGroupsDistinct() throws Exception {
        Map<String,Object> factors = a1();
        factors.put("research_hs300_distance_ma60",-.00001);
        assertThat(run(factors).triggeredRules()).containsExactly(CODES.get(0));
        factors.put("research_rsi14",30.00001);
        assertThat(run(factors).triggeredRules()).isEmpty();
        factors.put("research_rsi14",30);
        factors.put("research_hs300_close_position",.200001);
        factors.put("research_hs300_distance_ma60",0);
        assertThat(run(factors).triggeredRules()).containsExactly(CODES.get(1));
    }

    private RuleExecutionResult run(Map<String,Object> factors) throws Exception {
        List<RuleDefinition> rules = new ArrayList<>();
        for (int i=0;i<CODES.size();i++) {
            String code=CODES.get(i);
            String drl=new ClassPathResource("rules/"+code+".drl").getContentAsString(StandardCharsets.UTF_8);
            rules.add(RuleDefinition.builder().ruleCode(code).ruleName(code).ruleContent(drl)
                    .ruleFormat("drools").status("active").enabled(true).priority(110-i).version("v1").build());
        }
        return executor.execute(new RuleExecutionRequest("000062.SZ",LocalDate.of(2026,9,30),factors,rules));
    }
    private Map<String,Object> base() {
        Map<String,Object> result=new HashMap<>();
        result.put("research_rebound_version","g144-g118-v1");
        result.put("research_rebound_eligible",true);
        return result;
    }
    private Map<String,Object> a1() {
        Map<String,Object> result=base();
        result.putAll(Map.of("research_rsi14",30,"research_close_position",.2,"research_volume_ratio_5d",1.5,
                "research_hs300_close_position",.2,"research_hs300_distance_ma60",0));
        return result;
    }
    private RuleStrategyDetailDto strategy() {
        RuleStrategyDetailDto strategy=new RuleStrategyDetailDto();
        strategy.setStrategyCode("RS_G144_G118_SZ125");strategy.setVersion("v1");
        List<RuleStrategyGroupDto> groups=new ArrayList<>();
        for(int i=0;i<2;i++) {
            RuleGroupDetailDto group=new RuleGroupDetailDto();group.setGroupCode(i==0?"G144":"G118");
            group.setAggregation("OR");group.setMinMatchedRules(1);group.setVersion("v1");
            List<RuleGroupMemberDto> members=new ArrayList<>();
            for(String code:List.of(CODES.get(i),CODES.get(2))) {
                RuleGroupMemberDto m=new RuleGroupMemberDto();m.setRuleCode(code);m.setWeight(BigDecimal.ONE);m.setRequired(false);members.add(m);
            }
            group.setMembers(members);
            RuleStrategyGroupDto selected=new RuleStrategyGroupDto();selected.setGroupCode(group.getGroupCode());
            selected.setGroup(group);selected.setWeight(BigDecimal.ONE);selected.setRequired(false);groups.add(selected);
        }
        strategy.setGroups(groups);return strategy;
    }
}
