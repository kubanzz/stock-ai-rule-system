package com.jx.tracker.rule.service;

import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import java.util.List;

/** Saved application membership is immutable within a strategy version. */
public final class StrategyStockScope {
    private StrategyStockScope() { }

    public static boolean isBound(RuleStrategyDetailDto strategy) {
        return strategy != null && strategy.getStockPoolType() != null
                && !"all".equals(strategy.getStockPoolType());
    }

    public static List<String> symbols(RuleStrategyDetailDto strategy) {
        if (strategy == null || !"watchlist".equals(strategy.getStockPoolType())
                || strategy.getStockPoolSymbols() == null) return List.of();
        return strategy.getStockPoolSymbols().stream().filter(s -> s != null && !s.isBlank())
                .distinct().toList();
    }

    public static boolean includes(RuleStrategyDetailDto strategy, String symbol) {
        return !isBound(strategy) || symbols(strategy).contains(symbol);
    }
}
