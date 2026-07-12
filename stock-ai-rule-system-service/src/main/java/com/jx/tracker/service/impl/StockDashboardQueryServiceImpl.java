package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.constant.StockRiskConstants;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.service.StockDashboardQueryService;
import com.jx.tracker.service.StockMarketContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockDashboardQueryServiceImpl implements StockDashboardQueryService {

    private static final String PENDING_SIGNAL = "pending";
    private static final Set<String> SUPPORTED_SIGNALS = Set.of(
            SignalType.BULLISH.getCode(),
            SignalType.BEARISH.getCode(),
            SignalType.WATCH.getCode(),
            SignalType.HIGH_RISK.getCode()
    );

    private final StockBaseMapper stockBaseMapper;
    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final StockDailyQuoteMapper stockDailyQuoteMapper;
    private final StockActualResultMapper stockActualResultMapper;
    private final StockWatchlistMapper stockWatchlistMapper;
    private final StockWatchlistItemMapper stockWatchlistItemMapper;
    private final StockMarketContextService stockMarketContextService;

    @Override
    public StockConsoleVo.SignalDashboardOverview dashboard(StockConsoleVo.SignalDashboardQuery input) {
        StockConsoleVo.SignalDashboardQuery query = input == null
                ? new StockConsoleVo.SignalDashboardQuery(null, null, null, null, null, null,
                null, null, 1, 20, null, null)
                : input;
        List<String> marketAliases = MarketCodeNormalizer.aliases(query.market());
        List<StockBase> marketStocks = stockBaseMapper.selectList(new LambdaQueryWrapper<StockBase>()
                .in(StockBase::getMarket, marketAliases)
                .orderByAsc(StockBase::getSymbol));
        List<String> availableIndustries = marketStocks.stream()
                .filter(stock -> MarketCodeNormalizer.equivalent(query.market(), stock.getMarket()))
                .map(StockBase::getIndustry)
                .filter(StringUtils::hasText)
                .distinct()
                .sorted()
                .toList();

        List<StockBase> candidates = filterStocks(marketStocks, query);
        if (!"all".equalsIgnoreCase(query.poolCode())) {
            candidates = restrictToPool(candidates, query);
        }
        if (candidates.isEmpty()) {
            return overview(query, query.date(), List.of(), metrics(0, List.of(), List.of()),
                    0, availableIndustries, null,
                    stockMarketContextService.marketContext(query, query.date(), candidates));
        }

        List<String> candidateSymbols = candidates.stream()
                .map(StockBase::getSymbol)
                .map(SymbolNormalizer::normalize)
                .distinct()
                .toList();
        LocalDate tradeDate = resolveTradeDate(query.date(), candidateSymbols);
        Map<String, StockBase> stocksBySymbol = candidates.stream().collect(Collectors.toMap(
                StockBase::getSymbol,
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
        ));
        List<StockSignalDaily> signals = tradeDate == null
                ? List.of()
                : stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(StockSignalDaily::getSignalDate, tradeDate)
                .in(StockSignalDaily::getSymbol, candidateSymbols));
        Set<String> candidateSymbolSet = Set.copyOf(candidateSymbols);
        Map<String, StockSignalDaily> signalsBySymbol = signals.stream()
                .filter(signal -> Objects.equals(tradeDate, signal.getSignalDate()))
                .filter(signal -> candidateSymbolSet.contains(SymbolNormalizer.normalize(signal.getSymbol())))
                .collect(Collectors.toMap(
                        signal -> SymbolNormalizer.normalize(signal.getSymbol()),
                        Function.identity(),
                        StockDashboardQueryServiceImpl::newerSignal,
                        LinkedHashMap::new
                ));

        List<StockDailyQuote> quotes = tradeDate == null
                ? List.of()
                : stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getTradeDate, tradeDate)
                .in(StockDailyQuote::getSymbol, candidateSymbols));
        Map<String, StockDailyQuote> quotesBySymbol = quotes.stream()
                .filter(quote -> Objects.equals(tradeDate, quote.getTradeDate()))
                .filter(quote -> candidateSymbolSet.contains(SymbolNormalizer.normalize(quote.getSymbol())))
                .collect(Collectors.toMap(
                        quote -> SymbolNormalizer.normalize(quote.getSymbol()),
                        Function.identity(),
                        StockDashboardQueryServiceImpl::newerQuote,
                        LinkedHashMap::new
                ));
        List<StockSignalDaily> readySignals = signalsBySymbol.values().stream()
                .filter(this::isReadySignal)
                .filter(signal -> matchesSignalAndConfidence(signal, query))
                .toList();
        List<String> signalSymbols = readySignals.stream()
                .map(StockSignalDaily::getSymbol)
                .map(SymbolNormalizer::normalize)
                .distinct()
                .toList();
        Set<String> signalSymbolSet = Set.copyOf(signalSymbols);
        List<StockActualResult> actualResults = tradeDate == null || signalSymbols.isEmpty()
                ? List.of()
                : stockActualResultMapper.selectList(new LambdaQueryWrapper<StockActualResult>()
                .eq(StockActualResult::getSignalDate, tradeDate)
                .in(StockActualResult::getSymbol, signalSymbols));
        List<StockActualResult> filteredActualResults = actualResults.stream()
                .filter(result -> Objects.equals(tradeDate, result.getSignalDate()))
                .filter(result -> signalSymbolSet.contains(SymbolNormalizer.normalize(result.getSymbol())))
                .toList();

        List<StockConsoleVo.SignalRow> rows = candidates.stream()
                .map(stock -> {
                    String symbol = SymbolNormalizer.normalize(stock.getSymbol());
                    StockSignalDaily signal = signalsBySymbol.get(symbol);
                    return toRow(signal, stock, quotesBySymbol.get(symbol));
                })
                .filter(row -> matchesRow(row, query))
                .sorted(rowComparator(query.sortField(), query.sortOrder()))
                .toList();
        long requestedOffset = (long) (query.pageNum() - 1) * query.pageSize();
        int fromIndex = (int) Math.min(requestedOffset, rows.size());
        int toIndex = Math.min(fromIndex + query.pageSize(), rows.size());
        LocalDateTime dataUpdatedAt = latestUpdate(readySignals, quotesBySymbol.values(), filteredActualResults);

        return overview(query, tradeDate, rows.subList(fromIndex, toIndex),
                metrics(candidates.size(), readySignals, filteredActualResults), rows.size(),
                availableIndustries, dataUpdatedAt,
                stockMarketContextService.marketContext(query, tradeDate, candidates));
    }

    private LocalDate resolveTradeDate(LocalDate requestedDate, List<String> candidateSymbols) {
        if (requestedDate != null) {
            return requestedDate;
        }
        StockDailyQuote latestQuote = stockDailyQuoteMapper.selectOne(new LambdaQueryWrapper<StockDailyQuote>()
                .in(StockDailyQuote::getSymbol, candidateSymbols)
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last("LIMIT 1"));
        if (latestQuote != null) {
            return latestQuote.getTradeDate();
        }
        StockSignalDaily latest = stockSignalDailyMapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                .in(StockSignalDaily::getSymbol, candidateSymbols)
                .orderByDesc(StockSignalDaily::getSignalDate)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getSignalDate();
    }

    private List<StockBase> filterStocks(List<StockBase> stocks, StockConsoleVo.SignalDashboardQuery query) {
        String search = query.symbol();
        String normalized = StringUtils.hasText(search) ? SymbolNormalizer.normalize(search) : null;
        return stocks.stream()
                .filter(stock -> MarketCodeNormalizer.equivalent(query.market(), stock.getMarket()))
                .filter(stock -> !StringUtils.hasText(query.industry()) || query.industry().equals(stock.getIndustry()))
                .filter(stock -> !StringUtils.hasText(search)
                        || Objects.equals(normalized, SymbolNormalizer.normalize(stock.getSymbol()))
                        || containsIgnoreCase(stock.getName(), search))
                .toList();
    }

    private List<StockBase> restrictToPool(
            List<StockBase> candidates,
            StockConsoleVo.SignalDashboardQuery query
    ) {
        StockWatchlist watchlist = stockWatchlistMapper.selectOne(new LambdaQueryWrapper<StockWatchlist>()
                .eq(StockWatchlist::getPoolCode, query.poolCode())
                .in(StockWatchlist::getMarket, MarketCodeNormalizer.aliases(query.market()))
                .last("LIMIT 1"));
        if (watchlist == null || !MarketCodeNormalizer.equivalent(query.market(), watchlist.getMarket())) {
            throw new ServiceException("股票池不存在: " + query.poolCode());
        }
        List<StockWatchlistItem> items = stockWatchlistItemMapper.selectList(new LambdaQueryWrapper<StockWatchlistItem>()
                .eq(StockWatchlistItem::getWatchlistId, watchlist.getId()));
        Set<String> poolSymbols = items.stream()
                .map(StockWatchlistItem::getSymbol)
                .map(SymbolNormalizer::normalize)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return candidates.stream()
                .filter(stock -> poolSymbols.contains(SymbolNormalizer.normalize(stock.getSymbol())))
                .toList();
    }

    private StockConsoleVo.SignalDashboardOverview overview(
            StockConsoleVo.SignalDashboardQuery query,
            LocalDate tradeDate,
            List<StockConsoleVo.SignalRow> rows,
            List<StockConsoleVo.MetricCard> metrics,
            long total,
            List<String> availableIndustries,
            LocalDateTime dataUpdatedAt,
            StockConsoleVo.MarketContext marketContext
    ) {
        return new StockConsoleVo.SignalDashboardOverview(
                tradeDate,
                StockRiskConstants.SIGNAL_RISK_DISCLAIMER,
                metrics,
                rows,
                marketContext,
                total,
                query.pageNum(),
                query.pageSize(),
                availableIndustries,
                dataUpdatedAt
        );
    }

    private StockConsoleVo.SignalRow toRow(StockSignalDaily signal, StockBase stock, StockDailyQuote quote) {
        boolean ready = isReadySignal(signal);
        return new StockConsoleVo.SignalRow(
                SymbolNormalizer.normalize(stock.getSymbol()),
                stock.getName(),
                quote == null ? null : quote.getClosePrice(),
                quote == null ? null : quote.getChangePct(),
                ready ? signal.getSignal() : null,
                ready ? signal.getBullishScore() : null,
                ready ? signal.getBearishScore() : null,
                ready ? signal.getRiskScore() : null,
                ready ? signal.getConfidence() : null,
                ready ? triggeredRuleCount(signal.getTriggeredRules()) : 0,
                ready ? "3-5日" : null,
                max(ready ? signal.getCreatedTime() : null, quote == null ? null : quote.getSyncTime()),
                ready ? "ready" : PENDING_SIGNAL,
                quote == null ? PENDING_SIGNAL : "ready"
        );
    }

    private boolean matchesRow(StockConsoleVo.SignalRow row, StockConsoleVo.SignalDashboardQuery query) {
        if (PENDING_SIGNAL.equalsIgnoreCase(query.signal())) {
            return PENDING_SIGNAL.equals(row.signalStatus());
        }
        if (StringUtils.hasText(query.signal()) && !query.signal().equals(row.signal())) {
            return false;
        }
        if (query.confidenceMin() != null || query.confidenceMax() != null) {
            if (row.confidence() == null) {
                return false;
            }
            return (query.confidenceMin() == null || row.confidence().compareTo(query.confidenceMin()) >= 0)
                    && (query.confidenceMax() == null || row.confidence().compareTo(query.confidenceMax()) <= 0);
        }
        return true;
    }

    private boolean matchesSignalAndConfidence(
            StockSignalDaily signal,
            StockConsoleVo.SignalDashboardQuery query) {
        if (PENDING_SIGNAL.equalsIgnoreCase(query.signal())) {
            return false;
        }
        if (StringUtils.hasText(query.signal()) && !query.signal().equals(signal.getSignal())) {
            return false;
        }
        if (query.confidenceMin() != null || query.confidenceMax() != null) {
            if (signal.getConfidence() == null) {
                return false;
            }
            return (query.confidenceMin() == null || signal.getConfidence().compareTo(query.confidenceMin()) >= 0)
                    && (query.confidenceMax() == null || signal.getConfidence().compareTo(query.confidenceMax()) <= 0);
        }
        return true;
    }

    private boolean isReadySignal(StockSignalDaily signal) {
        return signal != null
                && signal.getSignal() != null
                && SUPPORTED_SIGNALS.contains(signal.getSignal());
    }

    private List<StockConsoleVo.MetricCard> metrics(
            int candidateCount,
            List<StockSignalDaily> signals,
            List<StockActualResult> actualResults
    ) {
        long bullish = countSignal(signals, SignalType.BULLISH.getCode());
        long bearish = countSignal(signals, SignalType.BEARISH.getCode());
        long watch = countSignal(signals, SignalType.WATCH.getCode());
        long highRisk = countSignal(signals, SignalType.HIGH_RISK.getCode());
        List<Boolean> hit5dSamples = actualResults.stream()
                .map(StockActualResult::getHit5d)
                .filter(Objects::nonNull)
                .toList();
        long hits = hit5dSamples.stream().filter(Boolean.TRUE::equals).count();
        BigDecimal hitRate = hit5dSamples.isEmpty()
                ? null
                : BigDecimal.valueOf(hits).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(hit5dSamples.size()), 2, RoundingMode.HALF_UP);
        return List.of(
                metric("关注股票", candidateCount, "只", "blue"),
                metric("产生信号", signals.size(), "条", "cyan"),
                metric("看涨", bullish, "条", "green"),
                metric("看跌", bearish, "条", "red"),
                metric("观望", watch, "条", "gold"),
                metric("高风险", highRisk, "条", "purple"),
                new StockConsoleVo.MetricCard("命中率（5日）", hitRate, "%", BigDecimal.ZERO, "green")
        );
    }

    private StockConsoleVo.MetricCard metric(String label, long value, String unit, String tone) {
        return new StockConsoleVo.MetricCard(label, BigDecimal.valueOf(value), unit, BigDecimal.ZERO, tone);
    }

    private long countSignal(List<StockSignalDaily> signals, String signalType) {
        return signals.stream().filter(signal -> signalType.equals(signal.getSignal())).count();
    }

    private Comparator<StockConsoleVo.SignalRow> rowComparator(String field, String order) {
        Comparator<StockConsoleVo.SignalRow> primary = switch (field) {
            case "symbol" -> Comparator.comparing(StockConsoleVo.SignalRow::symbol,
                    valueComparator(order));
            case "price" -> Comparator.comparing(StockConsoleVo.SignalRow::price, valueComparator(order));
            case "changePct" -> Comparator.comparing(StockConsoleVo.SignalRow::changePct, valueComparator(order));
            case "signal" -> Comparator.comparing(StockConsoleVo.SignalRow::signal, valueComparator(order));
            case "bullishScore" -> Comparator.comparing(StockConsoleVo.SignalRow::bullishScore, valueComparator(order));
            case "bearishScore" -> Comparator.comparing(StockConsoleVo.SignalRow::bearishScore, valueComparator(order));
            case "riskScore" -> Comparator.comparing(StockConsoleVo.SignalRow::riskScore, valueComparator(order));
            case "triggeredRuleCount" -> Comparator.comparing(StockConsoleVo.SignalRow::triggeredRuleCount,
                    valueComparator(order));
            case "updatedAt" -> Comparator.comparing(StockConsoleVo.SignalRow::updatedAt, valueComparator(order));
            default -> Comparator.comparing(StockConsoleVo.SignalRow::confidence, valueComparator(order));
        };
        return primary.thenComparing(StockConsoleVo.SignalRow::symbol,
                Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private <T extends Comparable<? super T>> Comparator<T> valueComparator(String order) {
        Comparator<T> natural = Comparator.naturalOrder();
        return "asc".equals(order)
                ? Comparator.nullsLast(natural)
                : Comparator.nullsLast(natural.reversed());
    }

    private LocalDateTime latestUpdate(
            List<StockSignalDaily> signals,
            java.util.Collection<StockDailyQuote> quotes,
            List<StockActualResult> actualResults
    ) {
        List<LocalDateTime> updates = new ArrayList<>();
        signals.stream().map(StockSignalDaily::getCreatedTime).filter(Objects::nonNull).forEach(updates::add);
        quotes.stream().map(StockDailyQuote::getSyncTime).filter(Objects::nonNull).forEach(updates::add);
        actualResults.stream().map(StockActualResult::getCreatedTime).filter(Objects::nonNull).forEach(updates::add);
        return updates.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private static StockDailyQuote newerQuote(StockDailyQuote first, StockDailyQuote second) {
        return compareNullable(first.getSyncTime(), second.getSyncTime()) >= 0 ? first : second;
    }

    private static StockSignalDaily newerSignal(StockSignalDaily first, StockSignalDaily second) {
        return compareNullable(first.getCreatedTime(), second.getCreatedTime()) >= 0 ? first : second;
    }

    private static <T extends Comparable<? super T>> int compareNullable(T left, T right) {
        return Comparator.nullsFirst(Comparator.<T>naturalOrder()).compare(left, right);
    }

    private int compare(BigDecimal left, BigDecimal right) {
        return Comparator.nullsFirst(Comparator.<BigDecimal>naturalOrder()).compare(left, right);
    }

    private boolean containsIgnoreCase(String value, String search) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private int triggeredRuleCount(String triggeredRules) {
        return StringUtils.hasText(triggeredRules)
                ? (int) java.util.Arrays.stream(triggeredRules.split("[,，\\s]+"))
                .filter(StringUtils::hasText)
                .count()
                : 0;
    }

    private LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return compareNullable(first, second) >= 0 ? first : second;
    }
}
