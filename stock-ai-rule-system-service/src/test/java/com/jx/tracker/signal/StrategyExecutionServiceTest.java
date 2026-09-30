package com.jx.tracker.signal;

import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.rule.engine.RuleEvaluation;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.signal.service.StrategyExecutionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyExecutionServiceTest {

    private final StrategyExecutionService service = new StrategyExecutionService();

    @Test
    void selectsEachRuleOnceAndAssignsDuplicateContributionToHighestWeightGroup() {
        RuleStrategyDetailDto strategy = strategy(List.of(
                selected("trend", "WEIGHTED", 1, false, BigDecimal.ONE,
                        member("R_SHARED", "1", false), member("R_TREND", "1", false)),
                selected("volume", "WEIGHTED", 1, false, new BigDecimal("2"),
                        member("R_SHARED", "1.5", false))));
        List<RuleDefinition> available = List.of(rule("R_SHARED"), rule("R_TREND"), rule("R_OTHER"));

        assertThat(service.selectRules(strategy, available)).extracting(RuleDefinition::getRuleCode)
                .containsExactly("R_SHARED", "R_TREND");

        var result = service.aggregate(strategy, raw(
                evaluation("R_SHARED", "12", "0", "0", true),
                evaluation("R_TREND", "20", "0", "0", true)));

        assertThat(result.result().bullishScore()).isEqualByComparingTo("56");
        assertThat(result.result().triggeredRules()).containsExactly("R_SHARED", "R_TREND");
        assertThat(result.trace().groupContributions().get(0).weightedScores().bullish())
                .isEqualByComparingTo("20");
        assertThat(result.trace().groupContributions().get(1).weightedScores().bullish())
                .isEqualByComparingTo("36");
        assertThat(result.trace().ruleContributions().get(0).directionGroupCode()).isEqualTo("volume");
        assertThat(result.trace().ruleContributions().get(0).actualRuleVersion()).isEqualTo("v2");
    }

    @Test
    void requiredGroupFailureBlocksDirectionButKeepsRiskGate() {
        RuleStrategyDetailDto strategy = strategy(List.of(
                selected("trend", "AND", 2, true, BigDecimal.ONE,
                        member("R_BULL", "1", true), member("R_MISSING", "1", true)),
                selected("risk", "OR", 1, false, BigDecimal.ONE,
                        member("R_RISK", "1", false))));

        var result = service.aggregate(strategy, raw(
                evaluation("R_BULL", "80", "0", "0", true),
                evaluation("R_MISSING", "0", "0", "0", false),
                evaluation("R_RISK", "0", "0", "90", true)));

        assertThat(result.result().bullishScore()).isEqualByComparingTo("0");
        assertThat(result.result().riskScore()).isEqualByComparingTo("90");
        assertThat(result.result().triggeredRules()).containsExactly("R_RISK");
        assertThat(result.trace().requiredGroupGatePassed()).isFalse();
        assertThat(result.trace().unmetRequiredGroups()).containsExactly("trend");
    }

    @Test
    void requiredMemberBlocksGroupEvenWhenMinimumMatchCountIsReached() {
        RuleStrategyDetailDto strategy = strategy(List.of(selected("trend", "OR", 1, false,
                BigDecimal.ONE, member("R_BULL", "1", false), member("R_CONFIRM", "1", true))));

        var result = service.aggregate(strategy, raw(
                evaluation("R_BULL", "60", "0", "0", true),
                evaluation("R_CONFIRM", "0", "0", "0", false)));

        assertThat(result.result().bullishScore()).isEqualByComparingTo("0");
        assertThat(result.result().triggeredRules()).isEmpty();
        assertThat(result.trace().groupContributions().getFirst().eligible()).isFalse();
    }

    @Test
    void andRequiresAllMembersEvenWhenMinimumIsOne() {
        RuleStrategyDetailDto strategy = strategy(List.of(selected("trend", "AND", 1, false,
                BigDecimal.ONE, member("R_MATCH", "10", false),
                member("R_OTHER", "10", false))));

        var result = service.aggregate(strategy, raw(
                evaluation("R_MATCH", "40", "0", "0", true),
                evaluation("R_OTHER", "0", "0", "0", false)));

        assertThat(result.trace().groupContributions().getFirst().eligible()).isFalse();
        assertThat(result.result().bullishScore()).isEqualByComparingTo("0");
    }

    @Test
    void orIgnoresMinimumAndMemberWeightsButKeepsGroupWeight() {
        RuleStrategyDetailDto strategy = strategy(List.of(selected("trend", "OR", 2, false,
                new BigDecimal("2"), member("R_MATCH", "10", false),
                member("R_OTHER", "10", false))));

        var result = service.aggregate(strategy, raw(
                evaluation("R_MATCH", "20", "0", "3", true),
                evaluation("R_OTHER", "0", "0", "0", false)));

        assertThat(result.trace().groupContributions().getFirst().eligible()).isTrue();
        assertThat(result.result().bullishScore()).isEqualByComparingTo("40");
        assertThat(result.result().riskScore()).isEqualByComparingTo("6");
        assertThat(result.trace().ruleContributions().getFirst().directionWeight())
                .isEqualByComparingTo("2");
    }

    @Test
    void weightedRequiresMinimumAndAppliesMemberWeights() {
        RuleStrategyDetailDto strategy = strategy(List.of(selected("trend", "WEIGHTED", 2,
                false, new BigDecimal("2"), member("R_A", "1.5", false),
                member("R_B", "2", false))));

        var oneMatch = service.aggregate(strategy, raw(
                evaluation("R_A", "10", "0", "0", true),
                evaluation("R_B", "0", "0", "0", false)));
        assertThat(oneMatch.trace().groupContributions().getFirst().eligible()).isFalse();
        assertThat(oneMatch.result().bullishScore()).isEqualByComparingTo("0");

        var bothMatch = service.aggregate(strategy, raw(
                evaluation("R_A", "10", "0", "0", true),
                evaluation("R_B", "10", "0", "0", true)));
        assertThat(bothMatch.trace().groupContributions().getFirst().eligible()).isTrue();
        assertThat(bothMatch.result().bullishScore()).isEqualByComparingTo("70");
    }

    @Test
    void rejectsUnavailableActiveRuleRatherThanSilentlyChangingStrategy() {
        RuleStrategyDetailDto strategy = strategy(List.of(selected("trend", "OR", 1, false,
                BigDecimal.ONE, member("R_MISSING", "1", false))));

        assertThatThrownBy(() -> service.selectRules(strategy, List.of(rule("R_OTHER"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("R_MISSING");
    }

    @Test
    void capsPersistedScoresAndPreservesWeightedContributionsInTrace() {
        RuleStrategyDetailDto strategy = strategy(List.of(selected("amplified", "WEIGHTED", 1,
                false, new BigDecimal("100"), member("R_AMPLIFIED", "100", false))));

        var result = service.aggregate(strategy, raw(
                evaluation("R_AMPLIFIED", "100", "2", "3", true)));

        assertThat(result.result().bullishScore()).isEqualByComparingTo("100");
        assertThat(result.result().bearishScore()).isEqualByComparingTo("100");
        assertThat(result.result().riskScore()).isEqualByComparingTo("100");
        assertThat(result.trace().rawWeightedScores().bullish()).isEqualByComparingTo("1000000");
        assertThat(result.trace().groupContributions().getFirst().weightedScores().risk())
                .isEqualByComparingTo("30000");
        assertThat(result.trace().effectiveScores().risk()).isEqualByComparingTo("100");
        assertThat(result.trace().capApplied()).isTrue();
    }

    private RuleStrategyDetailDto strategy(List<RuleStrategyGroupDto> groups) {
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto();
        strategy.setStrategyCode("S_TEST");
        strategy.setVersion("v1");
        strategy.setGroups(groups);
        return strategy;
    }

    private RuleStrategyGroupDto selected(String code, String aggregation, int minimum,
                                          boolean required, BigDecimal weight,
                                          RuleGroupMemberDto... members) {
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setGroupCode(code);
        group.setVersion("v1");
        group.setAggregation(aggregation);
        group.setMinMatchedRules(minimum);
        group.setMembers(List.of(members));
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto();
        selected.setGroupCode(code);
        selected.setGroupVersion("v1");
        selected.setGroup(group);
        selected.setRequired(required);
        selected.setWeight(weight);
        return selected;
    }

    private RuleGroupMemberDto member(String code, String weight, boolean required) {
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode(code);
        member.setWeight(new BigDecimal(weight));
        member.setRequired(required);
        return member;
    }

    private RuleDefinition rule(String code) {
        return RuleDefinition.builder().ruleCode(code).build();
    }

    private RuleEvaluation evaluation(String code, String bullish, String bearish,
                                      String risk, boolean matched) {
        return new RuleEvaluation(code, code, "v2", "DROOLS", 1,
                matched ? "MATCHED" : "NOT_MATCHED", List.of(),
                new BigDecimal(bullish), new BigDecimal(bearish), new BigDecimal(risk),
                matched ? code + " triggered" : null, "PARTIAL");
    }

    private RuleExecutionResult raw(RuleEvaluation... evaluations) {
        return new RuleExecutionResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                List.of(), List.of(), List.of(evaluations));
    }
}
