package com.jx.tracker.service;

import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.dto.RuleStrategyGroupDto;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.domain.dto.RuleGroupMemberDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.factor.P4VoteFactorCalculator;
import com.jx.tracker.factor.ResearchReboundFactorCalculator;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.rule.service.RuleStrategyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchReboundApplicationScopeTest {
    @Test
    void researchWarmupFollowsReferencedRulesAndPoolIntersectionRegardlessOfStrategyCode() {
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        ResearchReboundFactorCalculator calculator = mock(ResearchReboundFactorCalculator.class);
        when(calculator.getSymbols()).thenReturn(Set.of("000547.SZ", "002010.SZ"));
        var scope = new ResearchReboundApplicationScope(strategies, calculator, mock(StockDailyQuoteMapper.class));
        assertThat(scope.activeSymbols()).isEmpty();
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        active.setStrategyCode("another-plan");
        active.setStockPoolType("watchlist");
        active.setStockPoolSymbols(List.of("000547.SZ", "600000.SH"));
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));
        assertThat(scope.activeSymbols()).isEmpty();
        active.setGroups(List.of(researchGroup()));
        assertThat(scope.activeSymbols()).containsExactly("000547.SZ");
        active.setStockPoolType("all");
        assertThat(scope.activeSymbols()).containsExactlyInAnyOrder("000547.SZ", "002010.SZ");
    }

    @Test
    void everyBoundPlanUsesItsFrozenSnapshotAndIntersectsExplicitRequests() {
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        var scope = new ResearchReboundApplicationScope(strategies,
                mock(ResearchReboundFactorCalculator.class), mock(StockDailyQuoteMapper.class));
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        active.setStrategyCode("custom-plan");
        active.setStockPoolType("watchlist");
        active.setStockPoolSymbols(List.of("sz000547", "600000.SH", "000547.SZ"));
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));

        assertThat(scope.hasBoundStockPool()).isTrue();
        assertThat(scope.activeApplicationSymbols()).containsExactly("000547.SZ", "600000.SH");
        assertThat(scope.restrictToApplication(null)).containsExactly("000547.SZ", "600000.SH");
        assertThat(scope.restrictToApplication(List.of())).containsExactly("000547.SZ", "600000.SH");
        assertThat(scope.restrictToApplication(List.of("000547", "600519.SH"))).containsExactly("000547.SZ");
        assertThat(scope.restrictToApplication(List.of("600519.SH"))).isEmpty();
        assertThat(scope.activeSymbols()).isEmpty();
    }

    @Test
    void emptyOrMissingBoundSnapshotNeverExpandsToMarketOrResearchUniverse() {
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        ResearchReboundFactorCalculator calculator = mock(ResearchReboundFactorCalculator.class);
        when(calculator.getSymbols()).thenReturn(Set.of("000547.SZ"));
        var scope = new ResearchReboundApplicationScope(strategies, calculator, mock(StockDailyQuoteMapper.class));
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        active.setStockPoolType("watchlist");
        active.setGroups(List.of(researchGroup()));
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));
        assertThat(scope.hasBoundStockPool()).isTrue();
        assertThat(scope.activeApplicationSymbols()).isEmpty();
        assertThat(scope.restrictToApplication(null)).isEmpty();
        assertThat(scope.restrictToApplication(List.of("000547.SZ"))).isEmpty();
        assertThat(scope.activeSymbols()).isEmpty();
        active.setStockPoolSymbols(List.of());
        assertThat(scope.restrictToApplication(List.of())).isEmpty();
        active.setStockPoolType("invalid");
        active.setStockPoolSymbols(List.of("000547.SZ"));
        assertThat(scope.hasBoundStockPool()).isTrue();
        assertThat(scope.activeApplicationSymbols()).isEmpty();
        assertThat(scope.restrictToApplication(List.of("000547.SZ"))).isEmpty();
        active.setStockPoolType("");
        assertThat(scope.hasBoundStockPool()).isTrue();
        assertThat(scope.restrictToApplication(null)).isEmpty();
    }

    @Test
    void unboundAndLegacyPlansKeepExplicitRequestsAndDoNotInventDefaultSymbols() {
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        var scope = new ResearchReboundApplicationScope(strategies,
                mock(ResearchReboundFactorCalculator.class), mock(StockDailyQuoteMapper.class));
        assertThat(scope.hasBoundStockPool()).isFalse();
        assertThat(scope.restrictToApplication(List.of("600000"))).containsExactly("600000.SH");
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));
        assertThat(scope.hasBoundStockPool()).isFalse();
        active.setStockPoolType("all");
        active.setStockPoolSymbols(List.of("000547.SZ"));
        assertThat(scope.activeApplicationSymbols()).isEmpty();
        assertThat(scope.restrictToApplication(null)).isEmpty();
    }

    @Test
    void initialHistoryUses450DaysButEstablishedHistoryKeeps60DayGapScan() {
        var quotes = mock(StockDailyQuoteMapper.class);
        var scope = new ResearchReboundApplicationScope(mock(RuleStrategyService.class),
                mock(ResearchReboundFactorCalculator.class), quotes);
        LocalDate date = LocalDate.of(2026, 9, 30);
        when(quotes.selectCount(any())).thenReturn(251L, 252L);
        assertThat(scope.historyStart("000547.SZ", date)).isEqualTo(date.minusDays(450));
        assertThat(scope.historyStart("000547.SZ", date)).isEqualTo(date.minusDays(60));
    }

    @Test
    void p4WarmupUsesItsOwnFrozenMembersAndApplicationIntersection() {
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        ResearchReboundFactorCalculator rebound = mock(ResearchReboundFactorCalculator.class);
        when(rebound.getSymbols()).thenReturn(Set.of("000547.SZ"));
        P4VoteFactorCalculator p4 = new P4VoteFactorCalculator();
        var scope = new ResearchReboundApplicationScope(strategies, rebound,
                mock(StockDailyQuoteMapper.class), p4);
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        active.setStrategyCode("custom-p4-copy");
        active.setStockPoolType("watchlist");
        active.setStockPoolSymbols(List.of("sh600116", "600000.SH"));
        active.setGroups(List.of(ruleGroup("R_P4_VOTE_001_OPEN_GAP")));
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));

        assertThat(scope.activeSymbols()).containsExactly("600116.SH");
        active.setStockPoolType("all");
        assertThat(scope.activeSymbols()).containsExactlyInAnyOrderElementsOf(p4.getSymbols()).hasSize(100);
        active.setStockPoolType("watchlist");
        active.setStockPoolSymbols(List.of());
        assertThat(scope.activeSymbols()).isEmpty();
    }

    @Test
    void p4WarmupCountsOnlyVerifiedUnadjustedStockRows() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "scope-test"), StockDailyQuote.class);
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        active.setGroups(List.of(ruleGroup("R_P4_VOTE_001_CHANGE_PCT_5D")));
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));
        var quotes = mock(StockDailyQuoteMapper.class);
        when(quotes.selectCount(any())).thenReturn(251L);
        var scope = new ResearchReboundApplicationScope(strategies,
                mock(ResearchReboundFactorCalculator.class), quotes, new P4VoteFactorCalculator());
        LocalDate date = LocalDate.of(2026, 9, 30);

        assertThat(scope.historyStart("600116.SH", date)).isEqualTo(date.minusDays(450));
        ArgumentCaptor<Wrapper<StockDailyQuote>> argument = ArgumentCaptor.forClass(Wrapper.class);
        verify(quotes).selectCount(argument.capture());
        AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) argument.getValue();
        assertThat(query.getSqlSegment()).containsPattern("data_?Source IN|data_source IN");
        assertThat(query.getParamNameValuePairs().values()).contains(
                P4VoteFactorCalculator.BAOSTOCK_RAW_SOURCE, P4VoteFactorCalculator.TUSHARE_RAW_SOURCE);
        assertThat(query.getParamNameValuePairs().values()).doesNotContain("aktools/akshare");
    }

    @Test
    void reboundWarmupRetainsItsExistingQuoteSourcePolicy() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "scope-test"), StockDailyQuote.class);
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        RuleStrategyDetailDto active = new RuleStrategyDetailDto();
        active.setGroups(List.of(researchGroup()));
        when(strategies.getActiveStrategies()).thenReturn(List.of(active));
        var quotes = mock(StockDailyQuoteMapper.class);
        when(quotes.selectCount(any())).thenReturn(252L);
        var scope = new ResearchReboundApplicationScope(strategies,
                mock(ResearchReboundFactorCalculator.class), quotes);
        LocalDate date = LocalDate.of(2026, 9, 30);

        assertThat(scope.historyStart("000009.SZ", date)).isEqualTo(date.minusDays(60));
        ArgumentCaptor<Wrapper<StockDailyQuote>> argument = ArgumentCaptor.forClass(Wrapper.class);
        verify(quotes).selectCount(argument.capture());
        AbstractWrapper<?, ?, ?> query = (AbstractWrapper<?, ?, ?>) argument.getValue();
        assertThat(query.getSqlSegment()).doesNotContain("data_source IN", "dataSource IN");
        assertThat(query.getParamNameValuePairs().values()).doesNotContain(P4VoteFactorCalculator.BAOSTOCK_RAW_SOURCE);
    }

    @Test
    void stockHistoryRequiresPositiveAmountButIndexDoesNot() {
        StockDailyQuote quote = StockDailyQuote.builder().tradeDate(LocalDate.of(2026, 9, 30))
                .openPrice(BigDecimal.TEN).highPrice(BigDecimal.TEN).lowPrice(BigDecimal.TEN)
                .closePrice(BigDecimal.TEN).volume(BigDecimal.ONE).dataSource("aktools/akshare").build();
        assertThat(ResearchReboundApplicationScope.validQuote(quote, true)).isFalse();
        assertThat(ResearchReboundApplicationScope.validQuote(quote, false)).isTrue();
        quote.setAmount(BigDecimal.ONE);
        assertThat(ResearchReboundApplicationScope.validQuote(quote, true)).isTrue();
        quote.setDataSource("mock");
        assertThat(ResearchReboundApplicationScope.validQuote(quote, true)).isFalse();
    }

    @Test
    void combinesApplicationPoolsButKeepsResearchWarmupWithinItsOwningPool() {
        RuleStrategyService strategies = mock(RuleStrategyService.class);
        ResearchReboundFactorCalculator calculator = mock(ResearchReboundFactorCalculator.class);
        when(calculator.getSymbols()).thenReturn(Set.of("000547.SZ", "002010.SZ"));
        RuleStrategyDetailDto research = new RuleStrategyDetailDto(); research.setStockPoolType("watchlist");
        research.setStockPoolSymbols(List.of("000547.SZ")); research.setGroups(List.of(researchGroup()));
        RuleStrategyDetailDto other = new RuleStrategyDetailDto(); other.setStockPoolType("watchlist");
        other.setStockPoolSymbols(List.of("002010.SZ", "600000.SH"));
        when(strategies.getActiveStrategies()).thenReturn(List.of(research, other));
        var scope = new ResearchReboundApplicationScope(strategies, calculator, mock(StockDailyQuoteMapper.class));

        assertThat(scope.hasBoundStockPool()).isTrue();
        assertThat(scope.activeApplicationSymbols()).containsExactly("000547.SZ", "002010.SZ", "600000.SH");
        assertThat(scope.restrictToApplication(List.of("600000.SH", "600519.SH"))).containsExactly("600000.SH");
        assertThat(scope.activeSymbols()).containsExactly("000547.SZ");
        other.setStockPoolType("all");
        assertThat(scope.hasBoundStockPool()).isFalse();
        assertThat(scope.restrictToApplication(List.of("600519.SH"))).containsExactly("600519.SH");
    }

    private RuleStrategyGroupDto researchGroup() {
        return ruleGroup("R_G144_A1_MARKET_LOW");
    }

    private RuleStrategyGroupDto ruleGroup(String ruleCode) {
        RuleGroupMemberDto member = new RuleGroupMemberDto();
        member.setRuleCode(ruleCode);
        RuleGroupDetailDto group = new RuleGroupDetailDto();
        group.setMembers(List.of(member));
        RuleStrategyGroupDto reference = new RuleStrategyGroupDto();
        reference.setGroup(group);
        return reference;
    }
}
