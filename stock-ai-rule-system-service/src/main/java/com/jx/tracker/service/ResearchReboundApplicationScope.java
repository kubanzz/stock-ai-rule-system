package com.jx.tracker.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.factor.P4VoteFactorCalculator;
import com.jx.tracker.factor.ResearchReboundFactorCalculator;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.rule.service.RuleStrategyService;
import com.jx.tracker.rule.service.StrategyStockScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves the active application's frozen stock scope and research data requirements. */
@Component
public class ResearchReboundApplicationScope {

    private static final Set<String> RESEARCH_RULE_CODES = Set.of(
            "R_G144_A1_MARKET_LOW", "R_G118_A1_MARKET_MA60", "R_G144_G118_A2_MARKET_MA20");
    private static final Set<String> P4_RULE_CODES = Set.of(
            "R_P4_VOTE_001_CHANGE_PCT_5D", "R_P4_VOTE_001_OPEN_GAP", "R_P4_VOTE_001_INDEX_CLOSE_POSITION");
    private final RuleStrategyService strategyService;
    private final ResearchReboundFactorCalculator calculator;
    private final P4VoteFactorCalculator p4Calculator;
    private final StockDailyQuoteMapper quoteMapper;

    public ResearchReboundApplicationScope(RuleStrategyService strategyService,
                                           ResearchReboundFactorCalculator calculator,
                                           StockDailyQuoteMapper quoteMapper) {
        this(strategyService, calculator, quoteMapper, new P4VoteFactorCalculator());
    }

    @Autowired
    public ResearchReboundApplicationScope(RuleStrategyService strategyService,
                                           ResearchReboundFactorCalculator calculator,
                                           StockDailyQuoteMapper quoteMapper,
                                           P4VoteFactorCalculator p4Calculator) {
        this.strategyService = strategyService;
        this.calculator = calculator;
        this.quoteMapper = quoteMapper;
        this.p4Calculator = p4Calculator;
    }

    /** A union can be bounded only when every enabled application is bounded. */
    public boolean hasBoundStockPool() {
        List<RuleStrategyDetailDto> active = strategyService.getActiveStrategies();
        return !active.isEmpty() && active.stream().allMatch(StrategyStockScope::isBound);
    }

    /** Shared market-data collection uses the union; inference remains application-specific. */
    public Set<String> activeApplicationSymbols() {
        Set<String> symbols = new LinkedHashSet<>();
        for (RuleStrategyDetailDto active : strategyService.getActiveStrategies()) {
            symbols.addAll(normalizedSymbols(StrategyStockScope.symbols(active)));
        }
        return Collections.unmodifiableSet(symbols);
    }

    public List<String> restrictToApplication(Collection<String> requestedSymbols) {
        Set<String> requested = normalizedSymbols(requestedSymbols);
        if (!hasBoundStockPool()) return List.copyOf(requested);
        Set<String> allowed = activeApplicationSymbols();
        if (requestedSymbols == null || requestedSymbols.isEmpty()) return List.copyOf(allowed);
        return requested.stream().filter(allowed::contains).toList();
    }

    /** Longer history is required only for a research application that includes this stock. */
    public Set<String> activeSymbols() {
        Set<String> symbols = new LinkedHashSet<>();
        for (RuleStrategyDetailDto active : strategyService.getActiveStrategies()) {
            Set<String> applicationSymbols = new LinkedHashSet<>();
            if (usesRules(active, RESEARCH_RULE_CODES)) applicationSymbols.addAll(calculator.getSymbols());
            if (usesRules(active, P4_RULE_CODES)) applicationSymbols.addAll(p4Calculator.getSymbols());
            if (StrategyStockScope.isBound(active)) {
                applicationSymbols.retainAll(normalizedSymbols(StrategyStockScope.symbols(active)));
            }
            symbols.addAll(applicationSymbols);
        }
        return Collections.unmodifiableSet(symbols);
    }

    private boolean usesRules(RuleStrategyDetailDto strategy, Set<String> ruleCodes) {
        return strategy != null && strategy.getGroups() != null && strategy.getGroups().stream()
                .filter(Objects::nonNull).map(group -> group.getGroup()).filter(Objects::nonNull)
                .filter(group -> group.getMembers() != null).flatMap(group -> group.getMembers().stream())
                .filter(Objects::nonNull).anyMatch(member -> ruleCodes.contains(member.getRuleCode()));
    }

    private Set<String> normalizedSymbols(Collection<String> symbols) {
        if (symbols == null) {
            return Set.of();
        }
        return symbols.stream().map(SymbolNormalizer::normalize).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public LocalDate historyStart(String symbol, LocalDate endDate) {
        boolean p4History = strategyService.getActiveStrategies().stream().anyMatch(active ->
                usesRules(active, P4_RULE_CODES) && StrategyStockScope.includes(active, SymbolNormalizer.normalize(symbol)))
                && p4Calculator.isMember(SymbolNormalizer.normalize(symbol));
        long validRows = quoteMapper.selectCount(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, symbol)
                .le(StockDailyQuote::getTradeDate, endDate)
                .isNotNull(StockDailyQuote::getDataSource)
                .ne(StockDailyQuote::getDataSource, "mock")
                .in(p4History, StockDailyQuote::getDataSource,
                        P4VoteFactorCalculator.BAOSTOCK_RAW_SOURCE, P4VoteFactorCalculator.TUSHARE_RAW_SOURCE)
                .gt(StockDailyQuote::getOpenPrice, BigDecimal.ZERO)
                .gt(StockDailyQuote::getHighPrice, BigDecimal.ZERO)
                .gt(StockDailyQuote::getLowPrice, BigDecimal.ZERO)
                .gt(StockDailyQuote::getClosePrice, BigDecimal.ZERO)
                .gt(StockDailyQuote::getVolume, BigDecimal.ZERO)
                .gt(StockDailyQuote::getAmount, BigDecimal.ZERO));
        // About 300 sessions provide room for holidays and short suspensions.
        return endDate.minusDays(validRows < ResearchReboundFactorCalculator.MIN_STOCK_HISTORY ? 450 : 60);
    }

    public static boolean validQuote(StockDailyQuote quote, boolean stock) {
        return quote != null && quote.getTradeDate() != null
                && quote.getDataSource() != null && !"mock".equalsIgnoreCase(quote.getDataSource())
                && positive(quote.getOpenPrice()) && positive(quote.getHighPrice())
                && positive(quote.getLowPrice()) && positive(quote.getClosePrice())
                && (!stock || positive(quote.getVolume()) && positive(quote.getAmount()));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
}
