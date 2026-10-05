package com.jx.tracker.signal.service;

import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.RuleVersion;
import com.jx.tracker.mapper.RuleVersionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import java.util.Objects;
import com.jx.tracker.rule.engine.RuleEvaluation;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.rule.service.StrategyStockScope;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared production and backtest aggregation for a selected rule strategy. */
@Service
public class StrategyExecutionService {

    // SignalScoringService classifies each score on a 0..100 scale. Keep the
    // persisted scores on that same scale even when configured weights amplify
    // raw contributions beyond the range of stock_signal_daily DECIMAL(10,4).
    private static final BigDecimal MAX_EFFECTIVE_SCORE = new BigDecimal("100");

    private final RuleVersionMapper versionMapper;

    public StrategyExecutionService() { this(null); }

    @Autowired
    public StrategyExecutionService(RuleVersionMapper versionMapper) {
        this.versionMapper = versionMapper;
    }

    public List<RuleDefinition> selectRules(RuleStrategyDetailDto strategy, List<RuleDefinition> availableRules) {
        if (strategy == null) {
            return availableRules == null ? List.of() : List.copyOf(availableRules);
        }
        Map<String, RuleGroupMemberDto> selectedMembers = new LinkedHashMap<>();
        for (RuleStrategyGroupDto selectedGroup : groups(strategy)) {
            for (RuleGroupMemberDto member : members(selectedGroup.getGroup())) {
                RuleGroupMemberDto previous = selectedMembers.putIfAbsent(member.getRuleCode(), member);
                if (previous != null && (!Objects.equals(previous.getRuleVersionId(), member.getRuleVersionId())
                        || !Objects.equals(previous.getRuleVersionNo(), member.getRuleVersionNo())
                        || !Objects.equals(previous.getRuleContent(), member.getRuleContent()))) {
                    throw new IllegalArgumentException("同一应用方案不能引用同一规则的不同版本：" + member.getRuleCode());
                }
            }
        }
        Map<String, RuleDefinition> selected = new LinkedHashMap<>();
        for (RuleDefinition rule : availableRules == null ? List.<RuleDefinition>of() : availableRules) {
            if (rule != null && selectedMembers.containsKey(rule.getRuleCode())) {
                selected.putIfAbsent(rule.getRuleCode(), pinnedRule(rule, selectedMembers.get(rule.getRuleCode())));
            }
        }
        Set<String> missing = new HashSet<>(selectedMembers.keySet());
        missing.removeAll(selected.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Selected strategy includes unavailable active rules: "
                    + String.join(", ", missing.stream().sorted().toList()));
        }
        return List.copyOf(selected.values());
    }

    /** Execute the content pinned by the group's saved snapshot, even after a later publication. */
    private RuleDefinition pinnedRule(RuleDefinition current, RuleGroupMemberDto member) {
        String expected = member.getRuleVersionNo();
        Long expectedId = member.getRuleVersionId();
        if (member.getRuleContent() != null && !member.getRuleContent().isBlank()) {
            RuleDefinition pinned = new RuleDefinition();
            BeanUtils.copyProperties(current, pinned);
            pinned.setRuleContent(member.getRuleContent());
            pinned.setRuleFormat(member.getRuleFormat() == null ? current.getRuleFormat() : member.getRuleFormat());
            pinned.setRuleName(member.getRuleName() == null ? current.getRuleName() : member.getRuleName());
            pinned.setPriority(member.getRulePriority() == null ? current.getPriority() : member.getRulePriority());
            pinned.setVersion(expected == null ? current.getVersion() : expected);
            pinned.setCurrentVersionNo(expected);
            pinned.setCurrentVersionId(expectedId);
            return pinned;
        }
        if (expected == null && expectedId == null) return current; // legacy group snapshots
        String currentVersion = current.getCurrentVersionNo() == null
                ? current.getVersion() : current.getCurrentVersionNo();
        if (expectedId == null && (expected == null || Objects.equals(expected, currentVersion))) return current;
        if (versionMapper == null) {
            throw new IllegalArgumentException("缺少方案冻结的规则版本：" + current.getRuleCode() + "/" + expected);
        }
        RuleVersion frozen = expectedId == null
                ? versionMapper.selectOne(Wrappers.<RuleVersion>lambdaQuery()
                    .eq(RuleVersion::getRuleId, current.getId()).eq(RuleVersion::getVersionNo, expected))
                : versionMapper.selectById(expectedId);
        if (frozen == null || !Objects.equals(current.getId(), frozen.getRuleId())
                || expected != null && !Objects.equals(expected, frozen.getVersionNo())
                || frozen.getRuleContent() == null || frozen.getRuleContent().isBlank()) {
            throw new IllegalArgumentException("缺少方案冻结的规则版本：" + current.getRuleCode() + "/" + expected);
        }
        RuleDefinition pinned = new RuleDefinition();
        BeanUtils.copyProperties(current, pinned);
        pinned.setRuleContent(frozen.getRuleContent());
        pinned.setVersion(frozen.getVersionNo());
        pinned.setCurrentVersionNo(frozen.getVersionNo());
        pinned.setCurrentVersionId(frozen.getId());
        return pinned;
    }

    public StrategyExecutionResult aggregate(RuleStrategyDetailDto strategy, RuleExecutionResult raw) {
        if (strategy == null) {
            return new StrategyExecutionResult(raw, null);
        }
        Map<String, RuleEvaluation> evaluationsByCode = new LinkedHashMap<>();
        for (RuleEvaluation evaluation : raw.ruleEvaluations() == null
                ? List.<RuleEvaluation>of() : raw.ruleEvaluations()) {
            if (evaluationsByCode.putIfAbsent(evaluation.code(), evaluation) != null) {
                throw new IllegalArgumentException("Duplicate rule evaluation: " + evaluation.code());
            }
        }
        List<RuleStrategyGroupDto> selectedGroups = groups(strategy);
        if (selectedGroups.isEmpty()) {
            throw new IllegalArgumentException("Selected strategy has no rule groups: "
                    + strategy.getStrategyCode());
        }
        List<GroupState> groupStates = new ArrayList<>();
        List<String> unmetRequiredGroups = new ArrayList<>();
        for (RuleStrategyGroupDto selectedGroup : selectedGroups) {
            RuleGroupDetailDto group = selectedGroup.getGroup();
            List<RuleGroupMemberDto> members = members(group);
            long matchedCount = members.stream()
                    .filter(member -> matched(evaluationsByCode.get(member.getRuleCode())))
                    .count();
            boolean requiredMembersMatched = members.stream()
                    .filter(member -> Boolean.TRUE.equals(member.getRequired()))
                    .allMatch(member -> matched(evaluationsByCode.get(member.getRuleCode())));
            boolean aggregationSatisfied = switch (group.getAggregation()) {
                case "AND" -> matchedCount == members.size();
                case "OR" -> matchedCount >= 1;
                case "WEIGHTED" -> matchedCount >= (group.getMinMatchedRules() == null
                        ? 1 : group.getMinMatchedRules());
                default -> throw new IllegalArgumentException("Unsupported rule group aggregation: "
                        + group.getAggregation());
            };
            boolean eligible = !members.isEmpty() && requiredMembersMatched && aggregationSatisfied;
            if (!eligible && Boolean.TRUE.equals(selectedGroup.getRequired())) {
                unmetRequiredGroups.add(selectedGroup.getGroupCode());
            }
            groupStates.add(new GroupState(selectedGroup, members, eligible, (int) matchedCount));
        }

        // One rule may be present in several groups. Assign its direction score to
        // exactly one eligible group, preferring the largest configured product.
        Map<String, Assignment> directionalAssignments = new HashMap<>();
        Map<String, Assignment> riskAssignments = new HashMap<>();
        for (int groupIndex = 0; groupIndex < groupStates.size(); groupIndex++) {
            GroupState state = groupStates.get(groupIndex);
            for (RuleGroupMemberDto member : state.members()) {
                RuleEvaluation evaluation = evaluationsByCode.get(member.getRuleCode());
                if (!matched(evaluation)) {
                    continue;
                }
                BigDecimal factor = weight(state.selection().getWeight());
                if ("WEIGHTED".equals(state.selection().getGroup().getAggregation())) {
                    factor = factor.multiply(weight(member.getWeight()));
                }
                Assignment candidate = new Assignment(groupIndex, member, factor);
                if (state.eligible()) {
                    directionalAssignments.merge(member.getRuleCode(), candidate, this::larger);
                }
                // A risk rule remains a safety gate even when its group or a
                // required strategy group did not satisfy the direction gate.
                riskAssignments.merge(member.getRuleCode(), candidate, this::larger);
            }
        }
        boolean directionGatePassed = unmetRequiredGroups.isEmpty();
        if (!directionGatePassed) {
            directionalAssignments.clear();
        }

        BigDecimal bullish = BigDecimal.ZERO;
        BigDecimal bearish = BigDecimal.ZERO;
        BigDecimal risk = BigDecimal.ZERO;
        List<RuleEvaluation> weightedEvaluations = new ArrayList<>();
        List<RuleContribution> contributions = new ArrayList<>();
        List<String> triggeredRules = new ArrayList<>();
        List<String> explanations = new ArrayList<>();
        Map<Integer, Scores> groupScores = new HashMap<>();
        Map<Integer, List<String>> groupRules = new HashMap<>();
        for (RuleEvaluation evaluation : evaluationsByCode.values()) {
            Assignment directionAssignment = directionalAssignments.get(evaluation.code());
            Assignment riskAssignment = riskAssignments.get(evaluation.code());
            BigDecimal bullDelta = weighted(evaluation.bullishDelta(), directionAssignment);
            BigDecimal bearDelta = weighted(evaluation.bearishDelta(), directionAssignment);
            BigDecimal riskDelta = weighted(evaluation.riskDelta(), riskAssignment);
            bullish = bullish.add(bullDelta);
            bearish = bearish.add(bearDelta);
            risk = risk.add(riskDelta);
            boolean counted = directionAssignment != null || riskAssignment != null && riskDelta.signum() != 0;
            if (counted) {
                triggeredRules.add(evaluation.code());
                if (evaluation.explanation() != null && !evaluation.explanation().isBlank()) {
                    explanations.add(evaluation.explanation());
                }
            }
            addToGroup(groupScores, groupRules, directionAssignment, evaluation.code(),
                    bullDelta, bearDelta, BigDecimal.ZERO);
            addToGroup(groupScores, groupRules, riskAssignment, evaluation.code(),
                    BigDecimal.ZERO, BigDecimal.ZERO, riskDelta);
            contributions.add(new RuleContribution(evaluation.code(), evaluation.version(),
                    evaluation.status(), directionAssignment == null ? null
                            : groupStates.get(directionAssignment.groupIndex()).selection().getGroupCode(),
                    riskAssignment == null ? null
                            : groupStates.get(riskAssignment.groupIndex()).selection().getGroupCode(),
                    directionAssignment == null ? BigDecimal.ZERO : directionAssignment.factor(),
                    riskAssignment == null ? BigDecimal.ZERO : riskAssignment.factor(),
                    new Scores(zero(evaluation.bullishDelta()), zero(evaluation.bearishDelta()),
                            zero(evaluation.riskDelta())),
                    new Scores(bullDelta, bearDelta, riskDelta)));
            weightedEvaluations.add(new RuleEvaluation(evaluation.code(), evaluation.name(),
                    evaluation.version(), evaluation.format(), evaluation.priority(), evaluation.status(),
                    evaluation.conditions(), bullDelta, bearDelta, riskDelta,
                    evaluation.explanation(), evaluation.evidenceStatus()));
        }
        List<GroupContribution> groupContributions = new ArrayList<>();
        for (int i = 0; i < groupStates.size(); i++) {
            GroupState state = groupStates.get(i);
            RuleGroupDetailDto group = state.selection().getGroup();
            groupContributions.add(new GroupContribution(group.getGroupCode(), group.getVersion(),
                    group.getAggregation(), Boolean.TRUE.equals(state.selection().getRequired()),
                    state.eligible(), state.matchedCount(), state.members().size(),
                    groupScores.getOrDefault(i, Scores.ZERO),
                    List.copyOf(groupRules.getOrDefault(i, List.of()))));
        }
        Scores rawWeightedScores = new Scores(bullish, bearish, risk);
        Scores effectiveScores = new Scores(effective(bullish), effective(bearish), effective(risk));
        RuleExecutionResult result = new RuleExecutionResult(effectiveScores.bullish(),
                effectiveScores.bearish(), effectiveScores.risk(),
                List.copyOf(triggeredRules), List.copyOf(explanations), List.copyOf(weightedEvaluations));
        List<SelectedGroup> selectedGroupSnapshot = selectedGroups.stream()
                .map(selected -> new SelectedGroup(selected.getGroupCode(), selected.getGroupVersion(),
                        weight(selected.getWeight()), Boolean.TRUE.equals(selected.getRequired())))
                .toList();
        StrategyExecutionTrace trace = new StrategyExecutionTrace(strategy.getStrategyCode(),
                strategy.getVersion(), selectedGroupSnapshot, List.copyOf(groupContributions),
                List.copyOf(contributions), rawWeightedScores, effectiveScores,
                !sameScores(rawWeightedScores, effectiveScores),
                directionGatePassed, List.copyOf(unmetRequiredGroups),
                new StockPoolSnapshot(strategy.getStockPoolType() == null ? "all" : strategy.getStockPoolType(),
                        strategy.getStockPoolCode(), strategy.getStockPoolName(),
                        StrategyStockScope.symbols(strategy)));
        return new StrategyExecutionResult(result, trace);
    }

    private List<RuleStrategyGroupDto> groups(RuleStrategyDetailDto strategy) {
        if (strategy.getGroups() == null) return List.of();
        return strategy.getGroups().stream().filter(group -> group != null && group.getGroup() != null).toList();
    }

    private List<RuleGroupMemberDto> members(RuleGroupDetailDto group) {
        return group == null || group.getMembers() == null ? List.of() : group.getMembers().stream()
                .filter(member -> member != null && member.getRuleCode() != null).toList();
    }

    private boolean matched(RuleEvaluation evaluation) {
        return evaluation != null && "MATCHED".equals(evaluation.status());
    }

    private Assignment larger(Assignment existing, Assignment candidate) {
        return existing.factor().compareTo(candidate.factor()) >= 0 ? existing : candidate;
    }

    private BigDecimal weighted(BigDecimal delta, Assignment assignment) {
        return assignment == null ? BigDecimal.ZERO : zero(delta).multiply(assignment.factor());
    }

    private BigDecimal weight(BigDecimal value) {
        return value == null ? BigDecimal.ONE : value;
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal effective(BigDecimal score) {
        return score.max(BigDecimal.ZERO).min(MAX_EFFECTIVE_SCORE);
    }

    private boolean sameScores(Scores left, Scores right) {
        return left.bullish().compareTo(right.bullish()) == 0
                && left.bearish().compareTo(right.bearish()) == 0
                && left.risk().compareTo(right.risk()) == 0;
    }

    private void addToGroup(Map<Integer, Scores> scores, Map<Integer, List<String>> rules,
                            Assignment assignment, String code,
                            BigDecimal bullish, BigDecimal bearish, BigDecimal risk) {
        if (assignment == null || bullish.signum() == 0 && bearish.signum() == 0 && risk.signum() == 0) {
            return;
        }
        scores.merge(assignment.groupIndex(), new Scores(bullish, bearish, risk), Scores::add);
        List<String> assigned = rules.computeIfAbsent(assignment.groupIndex(), ignored -> new ArrayList<>());
        if (!assigned.contains(code)) assigned.add(code);
    }

    private record GroupState(RuleStrategyGroupDto selection, List<RuleGroupMemberDto> members,
                              boolean eligible, int matchedCount) { }

    private record Assignment(int groupIndex, RuleGroupMemberDto member, BigDecimal factor) { }

    public record StrategyExecutionResult(RuleExecutionResult result, StrategyExecutionTrace trace) { }

    public record Scores(BigDecimal bullish, BigDecimal bearish, BigDecimal risk) {
        static final Scores ZERO = new Scores(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        Scores add(Scores other) {
            return new Scores(bullish.add(other.bullish), bearish.add(other.bearish), risk.add(other.risk));
        }
    }

    public record StrategyExecutionTrace(String strategyCode, String strategyVersion,
                                         List<SelectedGroup> selectedGroups,
                                         List<GroupContribution> groupContributions,
                                         List<RuleContribution> ruleContributions,
                                         Scores rawWeightedScores, Scores effectiveScores,
                                         boolean capApplied,
                                         boolean requiredGroupGatePassed,
                                         List<String> unmetRequiredGroups,
                                         StockPoolSnapshot stockPool) { }

    public record StockPoolSnapshot(String type, String code, String name, List<String> symbols) { }

    public record SelectedGroup(String groupCode, String groupVersion, BigDecimal weight,
                                boolean required) { }

    public record GroupContribution(String groupCode, String groupVersion, String aggregation,
                                    boolean required, boolean eligible, int matchedCount, int memberCount,
                                    Scores weightedScores, List<String> countedRules) { }

    public record RuleContribution(String ruleCode, String actualRuleVersion, String matchStatus,
                                   String directionGroupCode, String riskGroupCode,
                                   BigDecimal directionWeight, BigDecimal riskWeight,
                                   Scores originalScores, Scores weightedScores) { }
}
