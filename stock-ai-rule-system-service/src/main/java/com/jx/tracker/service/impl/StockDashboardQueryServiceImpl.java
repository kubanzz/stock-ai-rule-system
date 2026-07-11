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
import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.service.StockDashboardQueryService;
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

    private final StockBaseMapper stockBaseMapper;
    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final StockDailyQuoteMapper stockDailyQuoteMapper;
    private final StockActualResultMapper stockActualResultMapper;
    private final StockWatchlistMapper stockWatchlistMapper;
    private final StockWatchlistItemMapper stockWatchlistItemMapper;

    @Override
    public StockConsoleVo.SignalDashboardOverview dashboard(StockConsoleVo.SignalDashboardQuery input) {
        StockConsoleVo.SignalDashboardQuery query = input == null
                ? new StockConsoleVo.SignalDashboardQuery(null, null, null, null, null, null,
                null, null, 1, 20, null, null)
                : input;
        LocalDate tradeDate = resolveTradeDate(query.date());
        List<StockBase> marketStocks = stockBaseMapper.selectList(new LambdaQueryWrapper<StockBase>()
                .eq(StockBase::getMarket, query.market())
                .orderByAsc(StockBase::getSymbol));
        List<String> availableIndustries = marketStocks.stream()
                .filter(stock -> query.market().equals(stock.getMarket()))
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
            return overview(query, tradeDate, List.of(), metrics(0, List.of(), List.of()),
                    0, availableIndustries, null);
        }

        List<String> candidateSymbols = candidates.stream().map(StockBase::getSymbol).distinct().toList();
        Map<String, StockBase> stocksBySymbol = candidates.stream().collect(Collectors.toMap(
                StockBase::getSymbol,
                Function.identity(),
                (first, ignored) -> first,
                LinkedHashMap::new
        ));
        List<StockSignalDaily> signals = stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                .eq(tradeDate != null, StockSignalDaily::getSignalDate, tradeDate)
                .in(StockSignalDaily::getSymbol, candidateSymbols)
                .eq(StringUtils.hasText(query.signal()), StockSignalDaily::getSignal, query.signal())
                .ge(query.confidenceMin() != null, StockSignalDaily::getConfidence, query.confidenceMin())
                .le(query.confidenceMax() != null, StockSignalDaily::getConfidence, query.confidenceMax()));
        Set<String> candidateSymbolSet = Set.copyOf(candidateSymbols);
        List<StockSignalDaily> filteredSignals = signals.stream()
                .filter(signal -> Objects.equals(tradeDate, signal.getSignalDate()))
                .filter(signal -> candidateSymbolSet.contains(signal.getSymbol()))
                .filter(signal -> !StringUtils.hasText(query.signal()) || query.signal().equals(signal.getSignal()))
                .filter(signal -> query.confidenceMin() == null && query.confidenceMax() == null
                        || signal.getConfidence() != null)
                .filter(signal -> query.confidenceMin() == null || compare(signal.getConfidence(), query.confidenceMin()) >= 0)
                .filter(signal -> query.confidenceMax() == null || compare(signal.getConfidence(), query.confidenceMax()) <= 0)
                .toList();

        if (filteredSignals.isEmpty()) {
            return overview(query, tradeDate, List.of(), metrics(candidates.size(), List.of(), List.of()),
                    0, availableIndustries, null);
        }

        List<String> signalSymbols = filteredSignals.stream().map(StockSignalDaily::getSymbol).distinct().toList();
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(tradeDate != null, StockDailyQuote::getTradeDate, tradeDate)
                .in(StockDailyQuote::getSymbol, signalSymbols));
        Map<String, StockDailyQuote> quotesBySymbol = quotes.stream()
                .filter(quote -> Objects.equals(tradeDate, quote.getTradeDate()))
                .filter(quote -> candidateSymbolSet.contains(quote.getSymbol()))
                .collect(Collectors.toMap(
                        StockDailyQuote::getSymbol,
                        Function.identity(),
                        StockDashboardQueryServiceImpl::newerQuote,
                        LinkedHashMap::new
                ));
        List<StockActualResult> actualResults = stockActualResultMapper.selectList(new LambdaQueryWrapper<StockActualResult>()
                .eq(tradeDate != null, StockActualResult::getSignalDate, tradeDate)
                .in(StockActualResult::getSymbol, signalSymbols));
        List<StockActualResult> filteredActualResults = actualResults.stream()
                .filter(result -> Objects.equals(tradeDate, result.getSignalDate()))
                .filter(result -> signalSymbols.contains(result.getSymbol()))
                .toList();

        List<StockConsoleVo.SignalRow> rows = filteredSignals.stream()
                .map(signal -> toRow(signal, stocksBySymbol.get(signal.getSymbol()), quotesBySymbol.get(signal.getSymbol())))
                .sorted(rowComparator(query.sortField(), query.sortOrder()))
                .toList();
        long requestedOffset = (long) (query.pageNum() - 1) * query.pageSize();
        int fromIndex = (int) Math.min(requestedOffset, rows.size());
        int toIndex = Math.min(fromIndex + query.pageSize(), rows.size());
        LocalDateTime dataUpdatedAt = latestUpdate(filteredSignals, quotesBySymbol.values());

        return overview(query, tradeDate, rows.subList(fromIndex, toIndex),
                metrics(candidates.size(), filteredSignals, filteredActualResults), rows.size(),
                availableIndustries, dataUpdatedAt);
    }

    private LocalDate resolveTradeDate(LocalDate requestedDate) {
        if (requestedDate != null) {
            return requestedDate;
        }
        StockSignalDaily latest = stockSignalDailyMapper.selectOne(new LambdaQueryWrapper<StockSignalDaily>()
                .orderByDesc(StockSignalDaily::getSignalDate)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getSignalDate();
    }

    private List<StockBase> filterStocks(List<StockBase> stocks, StockConsoleVo.SignalDashboardQuery query) {
        String search = query.symbol();
        String normalized = StringUtils.hasText(search) ? SymbolNormalizer.normalize(search) : null;
        return stocks.stream()
                .filter(stock -> query.market().equals(stock.getMarket()))
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
                .eq(StockWatchlist::getMarket, query.market())
                .last("LIMIT 1"));
        if (watchlist == null) {
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
            LocalDateTime dataUpdatedAt
    ) {
        return new StockConsoleVo.SignalDashboardOverview(
                tradeDate,
                StockRiskConstants.SIGNAL_RISK_DISCLAIMER,
                metrics,
                rows,
                emptyMarketContext(),
                total,
                query.pageNum(),
                query.pageSize(),
                availableIndustries,
                dataUpdatedAt
        );
    }

    private StockConsoleVo.MarketContext emptyMarketContext() {
        return new StockConsoleVo.MarketContext(false, null, null, null, "unavailable",
                List.of(), List.of(),
                new StockConsoleVo.Sentiment("暂无数据", null, "unavailable"),
                new StockConsoleVo.RiskOverview(0, null, "unavailable", null, "暂无市场风险环境数据"));
    }

    private StockConsoleVo.SignalRow toRow(StockSignalDaily signal, StockBase stock, StockDailyQuote quote) {
        return new StockConsoleVo.SignalRow(
                signal.getSymbol(),
                stock == null ? signal.getSymbol() : stock.getName(),
                quote == null ? null : quote.getClosePrice(),
                quote == null ? null : quote.getChangePct(),
                signal.getSignal(),
                zeroIfNull(signal.getBullishScore()),
                zeroIfNull(signal.getBearishScore()),
                zeroIfNull(signal.getRiskScore()),
                zeroIfNull(signal.getConfidence()),
                triggeredRuleCount(signal.getTriggeredRules()),
                "3-5日",
                max(signal.getCreatedTime(), quote == null ? null : quote.getSyncTime())
        );
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
            java.util.Collection<StockDailyQuote> quotes
    ) {
        List<LocalDateTime> updates = new ArrayList<>();
        signals.stream().map(StockSignalDaily::getCreatedTime).filter(Objects::nonNull).forEach(updates::add);
        quotes.stream().map(StockDailyQuote::getSyncTime).filter(Objects::nonNull).forEach(updates::add);
        return updates.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private static StockDailyQuote newerQuote(StockDailyQuote first, StockDailyQuote second) {
        return compareNullable(first.getSyncTime(), second.getSyncTime()) >= 0 ? first : second;
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

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return compareNullable(first, second) >= 0 ? first : second;
    }
}
