package com.jx.tracker.signal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisMapperBuilderAssistant;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.entity.RuleDefinition;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.mapper.RuleDefinitionMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.rule.engine.RuleEvaluation;
import com.jx.tracker.rule.engine.RuleExecutionResult;
import com.jx.tracker.rule.service.RuleStrategyService;
import com.jx.tracker.signal.service.SignalScoringService;
import com.jx.tracker.signal.service.StockSignalService;
import com.jx.tracker.signal.service.StrategyExecutionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MultiStrategySignalGenerationTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 30);

    @Test
    void overlappingStockProducesIndependentSignalsAndVersionsWithoutRuleLeakage() {
        Fixture fixture = new Fixture();
        RuleStrategyDetailDto first = strategy("S_A", "v1", "R_A", "AAPL", "MSFT");
        RuleStrategyDetailDto second = strategy("S_B", "v2", "R_B", "AAPL");
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(first, second));

        List<StockSignalDaily> signals = fixture.service.generateDailySignalsFromFactors(DATE, List.of());

        assertThat(signals).hasSize(3);
        assertThat(signals).extracting(row -> row.getSymbol() + "/" + row.getStrategyCode() + "/" + row.getStrategyVersion())
                .containsExactly("AAPL/S_A/v1", "AAPL/S_B/v2", "MSFT/S_A/v1");
        assertThat(fixture.executed).containsExactly("AAPL:R_A", "AAPL:R_B", "MSFT:R_A");
        assertThat(signals.get(0).getId()).isNotEqualTo(signals.get(1).getId());
        fixture.service.generateDailySignalsFromFactors(DATE, List.of("AAPL"));
        assertThat(fixture.saved).hasSize(3); // same plan/version replaces only its own row
        first.setVersion("v2");
        fixture.service.generateDailySignals("AAPL", DATE, Map.of(), "S_A");
        assertThat(fixture.saved).hasSize(4); // old published plan version remains present
        assertThatThrownBy(() -> fixture.service.generateDailySignal("AAPL", DATE, Map.of()))
                .hasMessageContaining("多个启用方案");
    }

    @Test
    void backfillMissingSignalsIsPerApplicationAndNeverRewritesPublishedHistoricalSignal() {
        Fixture fixture = new Fixture();
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(
                strategy("S_A", "v1", "R_A", "AAPL"), strategy("S_B", "v1", "R_B", "AAPL")));
        StockSignalDaily existing = fixture.service.generateDailySignals("AAPL", DATE, Map.of(), "S_A").getFirst();
        existing.setGenerationType("backfill");
        assertThat(fixture.service.needsSignalGeneration("AAPL", DATE, false)).isTrue();

        List<StockSignalDaily> filled = fixture.service.backfillMissingSignalsFromFactors("AAPL", DATE, "backfill", false);

        assertThat(filled).extracting(StockSignalDaily::getStrategyCode).containsExactly("S_B");
        assertThat(fixture.saved).hasSize(2);
        assertThat(fixture.service.needsSignalGeneration("AAPL", DATE, true)).isFalse();
        assertThat(fixture.service.backfillMissingSignalsFromFactors("AAPL", DATE, "backfill", true)).isEmpty();
    }

    @Test
    void singularAnalysisRejectsAmbiguousSignalsAndExplicitIdSelectsExactPlan() {
        Fixture fixture = new Fixture();
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(
                strategy("S_A", "v1", "R_A", "AAPL"), strategy("S_B", "v1", "R_B", "AAPL")));
        List<StockSignalDaily> signals = fixture.service.generateDailySignals("AAPL", DATE, Map.of(), null);
        assertThatThrownBy(() -> fixture.service.getSignal("AAPL", DATE)).hasMessageContaining("多个方案信号");
        assertThat(fixture.service.getSignal("AAPL", DATE, signals.get(1).getId(), null, null)
                .getStrategyCode()).isEqualTo("S_B");
        assertThat(fixture.service.getSignal("MSFT", DATE, signals.get(1).getId(), null, null)).isNull();
        assertThat(fixture.service.getSignal("AAPL", DATE, signals.get(1).getId(), "S_A", "v1")).isNull();
    }

    @Test
    void unavailableResearchInputSkipsOnlyItsPlanAndP4MissingIndexDoesNotInvalidateTwoStockAtoms() {
        Fixture fixture = new Fixture();
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(
                strategy("S_REGULAR", "v1", "R_A", "AAPL"),
                strategy("P4-VOTE-001", "v1", "R_P4_VOTE_001_OPEN_GAP", "AAPL")));
        fixture.factorValues.put("p4_vote_001_version", "p4-vote-001-v1");
        fixture.factorValues.put("p4_vote_001_unavailable_reason", "insufficient_stock_history");
        var batch = fixture.service.backfillMissingSignalsBatch("AAPL", DATE, "backfill", false);
        assertThat(batch.signals()).extracting(StockSignalDaily::getStrategyCode).containsExactly("S_REGULAR");
        assertThat(batch.failures()).extracting(StockSignalService.SignalGenerationFailure::strategyCode)
                .containsExactly("P4-VOTE-001");
        fixture.factorValues.put("p4_vote_001_unavailable_reason", "");
        fixture.factorValues.put("p4_vote_001_index_unavailable_reason", "missing_current_benchmark_quote");
        var p4 = fixture.service.backfillMissingSignalsBatch("AAPL", DATE, "backfill", false);
        assertThat(p4.signals()).extracting(StockSignalDaily::getStrategyCode).containsExactly("P4-VOTE-001");
        assertThat(p4.failures()).isEmpty();
    }

    @Test
    void invalidPlanDoesNotPreventOtherPlanInNormalBatchOrBackfillAndExplicitSelectionFails() {
        Fixture fixture = new Fixture();
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(
                strategy("S_BAD", "v1", "R_A", "AAPL"),
                strategy("S_GOOD", "v1", "R_B", "AAPL")));
        fixture.failingRules.add("R_A");

        assertThat(fixture.service.generateDailySignals("AAPL", DATE, Map.of(), null))
                .extracting(StockSignalDaily::getStrategyCode).containsExactly("S_GOOD");
        assertThat(fixture.service.generateDailySignalsFromFactors(DATE, List.of("AAPL")))
                .extracting(StockSignalDaily::getStrategyCode).containsExactly("S_GOOD");
        assertThatThrownBy(() -> fixture.service.generateDailySignals("AAPL", DATE, Map.of(), "S_BAD"))
                .hasMessageContaining("规则执行失败").hasMessageContaining("R_A");
        assertThatThrownBy(() -> fixture.service.generateDailySignalsFromFactors(DATE, List.of("AAPL"), "S_BAD"))
                .hasMessageContaining("规则执行失败");

        fixture.saved.clear();
        var backfill = fixture.service.backfillMissingSignalsBatch("AAPL", DATE, "backfill", false);
        assertThat(backfill.signals()).extracting(StockSignalDaily::getStrategyCode).containsExactly("S_GOOD");
        assertThat(backfill.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.strategyCode()).isEqualTo("S_BAD");
            assertThat(failure.reason()).contains("规则执行失败", "R_A");
        });
        assertThat(fixture.saved.values()).extracting(StockSignalDaily::getStrategyCode).containsExactly("S_GOOD");
    }

    @Test
    void persistenceFailureStillEscapesTheBatchInsteadOfBeingTreatedAsBadRule() {
        Fixture fixture = new Fixture();
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(
                strategy("S_A", "v1", "R_A", "AAPL"), strategy("S_B", "v1", "R_B", "AAPL")));
        var failure = new org.springframework.dao.DataIntegrityViolationException("database write failed");
        doThrow(failure).when(fixture.mapper).upsertSignal(any());

        assertThatThrownBy(() -> fixture.service.generateDailySignals("AAPL", DATE, Map.of(), null))
                .isSameAs(failure);
        verify(fixture.mapper, times(1)).upsertSignal(any());
    }

    @Test
    void normalGenerationSkipsUnavailableResearchPlanAndNoActivePlanDoesNotRunGlobalRules() {
        Fixture fixture = new Fixture();
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of(
                strategy("S_REGULAR", "v1", "R_A", "AAPL"),
                strategy("P4-VOTE-001", "v1", "R_P4_VOTE_001_OPEN_GAP", "AAPL")));
        assertThat(fixture.service.generateDailySignals("AAPL", DATE, Map.of(), null))
                .extracting(StockSignalDaily::getStrategyCode).containsExactly("S_REGULAR");
        assertThatThrownBy(() -> fixture.service.generateDailySignals("AAPL", DATE, Map.of(), "P4-VOTE-001"))
                .hasMessageContaining("缺少方案专属因子版本");
        when(fixture.strategies.getActiveStrategies()).thenReturn(List.of());
        fixture.executed.clear();
        assertThat(fixture.service.generateDailySignalsFromFactors(DATE, List.of())).isEmpty();
        assertThat(fixture.executed).isEmpty();
        assertThatThrownBy(() -> fixture.service.generateDailySignal("AAPL", DATE, Map.of()))
                .hasMessageContaining("没有启用");
    }

    private static RuleStrategyDetailDto strategy(String code, String version, String rule, String... symbols) {
        RuleGroupMemberDto member = new RuleGroupMemberDto(); member.setRuleCode(rule);
        RuleGroupDetailDto group = new RuleGroupDetailDto(); group.setGroupCode("G_" + code);
        group.setVersion("v1"); group.setAggregation("OR"); group.setMembers(List.of(member));
        RuleStrategyGroupDto selected = new RuleStrategyGroupDto(); selected.setGroupCode(group.getGroupCode());
        selected.setGroupVersion("v1"); selected.setGroup(group);
        RuleStrategyDetailDto strategy = new RuleStrategyDetailDto(); strategy.setStrategyCode(code);
        strategy.setVersion(version); strategy.setGroups(List.of(selected)); strategy.setStockPoolType("watchlist");
        strategy.setStockPoolSymbols(List.of(symbols)); return strategy;
    }

    private static final class Fixture {
        final RuleStrategyService strategies = mock(RuleStrategyService.class);
        final StockSignalDailyMapper mapper = mock(StockSignalDailyMapper.class);
        final Map<String, StockSignalDaily> saved = new LinkedHashMap<>();
        final List<String> executed = new ArrayList<>();
        final Set<String> failingRules = new HashSet<>();
        final Map<String, Object> factorValues = new LinkedHashMap<>();
        final StockSignalService service;

        Fixture() {
            TableInfoHelper.initTableInfo(new MybatisMapperBuilderAssistant(new MybatisConfiguration(), "multi"), StockSignalDaily.class);
            var definitions = mock(RuleDefinitionMapper.class);
            when(definitions.selectList(any())).thenReturn(List.of(
                    RuleDefinition.builder().ruleCode("R_A").version("v1").build(),
                    RuleDefinition.builder().ruleCode("R_B").version("v1").build(),
                    RuleDefinition.builder().ruleCode("R_P4_VOTE_001_OPEN_GAP").version("v1").build()));
            var factors = mock(StockFactorDailyMapper.class);
            when(factors.selectList(any())).thenReturn(List.of(factor("AAPL"), factor("MSFT")));
            when(factors.selectOne(any())).thenAnswer(inv -> {
                var query = (AbstractWrapper<?, ?, ?>) inv.getArgument(0); query.getSqlSegment();
                return StockFactorDaily.builder().symbol(query.getParamNameValuePairs().containsValue("AAPL") ? "AAPL" : "MSFT")
                        .tradeDate(DATE).factorJson(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(factorValues)).build();
            });
            AtomicLong ids = new AtomicLong();
            when(mapper.upsertSignal(any())).thenAnswer(inv -> {
                StockSignalDaily row = inv.getArgument(0); String key = key(row);
                StockSignalDaily old = saved.get(key); row.setId(old == null ? ids.incrementAndGet() : old.getId());
                saved.put(key, row); return 1;
            });
            when(mapper.insertSignalIfAbsent(any())).thenAnswer(inv -> {
                StockSignalDaily row = inv.getArgument(0); String key = key(row);
                if (saved.containsKey(key)) return 0;
                row.setId(ids.incrementAndGet()); saved.put(key, row); return 1;
            });
            when(mapper.selectOne(any())).thenAnswer(inv -> {
                var query = (AbstractWrapper<?, ?, ?>) inv.getArgument(0); query.getSqlSegment();
                var values = query.getParamNameValuePairs().values();
                return saved.values().stream().filter(row -> values.contains(row.getSymbol()) && values.contains(row.getSignalDate())
                        && values.contains(row.getStrategyCode()) && values.contains(row.getStrategyVersion())).findFirst().orElse(null);
            });
            when(mapper.selectList(any())).thenAnswer(inv -> List.copyOf(saved.values()));
            when(mapper.selectById(any())).thenAnswer(inv -> saved.values().stream()
                    .filter(row -> row.getId().equals(inv.getArgument(0))).findFirst().orElse(null));
            service = new StockSignalService(definitions, factors, mapper, request -> {
                String rule = request.rules().getFirst().getRuleCode(); executed.add(request.symbol() + ":" + rule);
                if (failingRules.contains(rule)) throw new IllegalArgumentException("Invalid Drools rule content for " + rule);
                RuleEvaluation evaluation = new RuleEvaluation(rule, rule, "v1", "drools", 1, "MATCHED", List.of(),
                        new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO, rule, "COMPLETE");
                return new RuleExecutionResult(new BigDecimal("70"), BigDecimal.ZERO, BigDecimal.ZERO,
                        List.of(rule), List.of(rule), List.of(evaluation));
            }, new SignalScoringService(), strategies, new StrategyExecutionService());
        }
        private static StockFactorDaily factor(String symbol) {
            return StockFactorDaily.builder().symbol(symbol).tradeDate(DATE).factorJson("{}").build();
        }
        private static String key(StockSignalDaily row) {
            return row.getSymbol() + "/" + row.getSignalDate() + "/" + row.getStrategyCode() + "/" + row.getStrategyVersion();
        }
    }
}
