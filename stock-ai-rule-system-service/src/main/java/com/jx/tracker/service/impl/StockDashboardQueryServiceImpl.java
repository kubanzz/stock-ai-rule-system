package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.jx.tracker.risk.dashboard.StockDashboardRiskOverlay;
import com.jx.tracker.risk.dashboard.StockDashboardRiskReader;
import com.jx.tracker.risk.model.RiskHorizon;
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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
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
    private final StockDashboardRiskReader stockDashboardRiskReader;

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
            return overview(query, query.date(), List.of(), metrics(0, List.of(), List.of(), List.of()),
                    0, availableIndustries, null, null, null, null,
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
                .le(StockSignalDaily::getSignalDate, tradeDate)
                .in(StockSignalDaily::getSymbol, candidateSymbols)
                .eq(query.strategyCode() != null, StockSignalDaily::getStrategyCode, query.strategyCode())
                .eq(query.strategyVersion() != null, StockSignalDaily::getStrategyVersion, query.strategyVersion()));
        Set<String> candidateSymbolSet = Set.copyOf(candidateSymbols);
        Map<String, StockSignalDaily> signalsByIdentity = signals.stream()
                .filter(signal -> signal.getSignalDate() != null && !signal.getSignalDate().isAfter(tradeDate))
                .filter(signal -> candidateSymbolSet.contains(SymbolNormalizer.normalize(signal.getSymbol())))
                .filter(signal -> query.strategyCode() == null || query.strategyCode().equals(signal.getStrategyCode()))
                .filter(signal -> query.strategyVersion() == null || query.strategyVersion().equals(signal.getStrategyVersion()))
                .collect(Collectors.toMap(
                        this::signalIdentity,
                        Function.identity(),
                        StockDashboardQueryServiceImpl::newerSignal,
                        LinkedHashMap::new
                ));

        List<StockDailyQuote> quotes = tradeDate == null
                ? List.of()
                : stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                // Candidates may have different latest trading dates (for
                // example, suspended stocks). Keep the dashboard date as the
                // upper bound while selecting each symbol's newest usable bar.
                .le(StockDailyQuote::getTradeDate, tradeDate)
                .in(StockDailyQuote::getSymbol, candidateSymbols));
        Map<String, StockDailyQuote> quotesBySymbol = quotes.stream()
                .filter(quote -> quote.getTradeDate() != null && quote.getClosePrice() != null
                        && !quote.getTradeDate().isAfter(tradeDate))
                .filter(quote -> candidateSymbolSet.contains(SymbolNormalizer.normalize(quote.getSymbol())))
                .collect(Collectors.toMap(
                        quote -> SymbolNormalizer.normalize(quote.getSymbol()),
                        Function.identity(),
                        StockDashboardQueryServiceImpl::newerQuote,
                        LinkedHashMap::new
                ));
        List<StockSignalDaily> availableSignals = signalsByIdentity.values().stream()
                .filter(this::isReadySignal)
                .toList();
        List<StockSignalDaily> readySignals = availableSignals.stream()
                .filter(signal -> matchesSignalAndConfidence(signal, query))
                .toList();
        List<StockSignalDaily> currentSignals = readySignals.stream()
                .filter(signal -> isCurrentSignal(signal, quotesBySymbol, tradeDate))
                .toList();
        List<StockSignalDaily> historicalSignals = readySignals.stream()
                .filter(signal -> !isCurrentSignal(signal, quotesBySymbol, tradeDate))
                .toList();
        List<String> signalSymbols = currentSignals.stream()
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
                .filter(result -> tradeDate.equals(result.getSignalDate()))
                .filter(result -> signalSymbolSet.contains(SymbolNormalizer.normalize(result.getSymbol())))
                .filter(result -> currentSignals.stream().anyMatch(signal -> matchesActual(signal, result)))
                .toList();

        List<StockConsoleVo.SignalRow> rows = candidates.stream()
                .flatMap(stock -> {
                    String symbol = SymbolNormalizer.normalize(stock.getSymbol());
                    List<StockSignalDaily> stockSignals = signalsByIdentity.values().stream()
                            .filter(signal -> symbol.equals(SymbolNormalizer.normalize(signal.getSymbol()))).toList();
                    if (stockSignals.isEmpty()) return java.util.stream.Stream.of(toRow(null, stock, quotesBySymbol.get(symbol), tradeDate));
                    return stockSignals.stream().map(signal -> toRow(signal, stock, quotesBySymbol.get(symbol), tradeDate));
                })
                .filter(row -> matchesRow(row, query))
                .sorted(rowComparator(query.sortField(), query.sortOrder()))
                .toList();
        long requestedOffset = (long) (query.pageNum() - 1) * query.pageSize();
        int fromIndex = (int) Math.min(requestedOffset, rows.size());
        int toIndex = Math.min(fromIndex + query.pageSize(), rows.size());
        LocalDateTime dataUpdatedAt = latestUpdate(readySignals, quotesBySymbol.values(), filteredActualResults);
        LocalDate latestSignalDate = availableSignals.stream()
                .map(StockSignalDaily::getSignalDate)
                .max(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime signalUpdatedAt = availableSignals.stream()
                .map(this::signalGeneratedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        LocalDateTime quoteUpdatedAt = quotesBySymbol.values().stream()
                .map(StockDailyQuote::getSyncTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        List<StockConsoleVo.SignalRow> pageRows = rows.subList(fromIndex, toIndex);
        List<StockConsoleVo.SignalRow> riskRows = attachRisk(
                pageRows, tradeDate, query.riskHorizon());
        return overview(query, tradeDate, riskRows,
                metrics(candidates.size(), currentSignals, historicalSignals, filteredActualResults), rows.size(),
                availableIndustries, dataUpdatedAt, latestSignalDate, signalUpdatedAt, quoteUpdatedAt,
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
            LocalDate latestSignalDate,
            LocalDateTime signalUpdatedAt,
            LocalDateTime quoteUpdatedAt,
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
                dataUpdatedAt,
                query.riskHorizon(),
                latestSignalDate,
                signalUpdatedAt,
                quoteUpdatedAt
        );
    }

    private StockConsoleVo.SignalRow toRow(
            StockSignalDaily signal, StockBase stock, StockDailyQuote quote, LocalDate tradeDate) {
        boolean ready = isReadySignal(signal);
        String freshness = !ready ? "missing"
                : isCurrentSignal(signal, quote, tradeDate) ? "current" : "historical";
        return new StockConsoleVo.SignalRow(
                SymbolNormalizer.normalize(stock.getSymbol()),
                stock.getName(),
                quote == null ? null : quote.getClosePrice(),
                quote == null ? null : quote.getChangePct(),
                ready ? displaySignal(signal) : null,
                ready ? signal.getBullishScore() : null,
                ready ? signal.getBearishScore() : null,
                ready ? signal.getRiskScore() : null,
                ready ? signal.getConfidence() : null,
                ready ? triggeredRuleCount(signal.getTriggeredRules()) : 0,
                ready ? "P4-VOTE-001".equals(signal.getStrategyCode())
                        ? "1日（T+1开盘至T+2开盘）" : "3-5日" : null,
                max(ready ? signalRecordUpdatedAt(signal) : null, quote == null ? null : quote.getSyncTime()),
                ready ? "ready" : PENDING_SIGNAL,
                quote == null ? PENDING_SIGNAL : "ready",
                ready ? signal.getSignalDate() : null,
                quote == null ? null : quote.getTradeDate(),
                ready ? signalGeneratedAt(signal) : null,
                freshness,
                ready ? signal.getGenerationType() : null,
                null,
                null,
                ready ? signal.getId() : null,
                ready ? signal.getStrategyCode() : null,
                ready ? signal.getStrategyVersion() : null,
                ready ? strategyName(signal) : null
        );
    }

    private String signalIdentity(StockSignalDaily signal) {
        return SymbolNormalizer.normalize(signal.getSymbol()) + "|" + signal.getStrategyCode() + "|" + signal.getStrategyVersion();
    }

    private boolean matchesActual(StockSignalDaily signal, StockActualResult result) {
        if (result.getSignalId() != null) return result.getSignalId().equals(signal.getId());
        return Objects.equals(signal.getStrategyCode(), result.getStrategyCode())
                && Objects.equals(signal.getStrategyVersion(), result.getStrategyVersion());
    }

    private String strategyName(StockSignalDaily signal) {
        if (StockSignalDaily.LEGACY_STRATEGY_CODE.equals(signal.getStrategyCode())) return "历史默认规则";
        return StringUtils.hasText(signal.getStrategyName()) ? signal.getStrategyName() : signal.getStrategyCode();
    }

    private List<StockConsoleVo.SignalRow> attachRisk(
            List<StockConsoleVo.SignalRow> rows,
            LocalDate tradeDate,
            RiskHorizon horizon
    ) {
        if (rows.isEmpty() || tradeDate == null) {
            return rows;
        }
        LocalDateTime asOf = LocalDateTime.now();
        List<String> symbols = rows.stream().map(StockConsoleVo.SignalRow::symbol).distinct().toList();
        Map<String, StockDashboardRiskOverlay> overlays = stockDashboardRiskReader.findBySymbols(
                tradeDate, horizon, symbols, asOf);
        Map<String, StockDashboardRiskOverlay> safeOverlays = overlays == null ? Map.of() : overlays;
        List<StockSignalDaily> signals = rows.stream().filter(row -> row.signalId() != null)
                .map(row -> StockSignalDaily.builder().id(row.signalId()).symbol(row.symbol())
                        .signalDate(row.signalDate()).strategyCode(row.strategyCode())
                        .strategyVersion(row.strategyVersion()).build()).toList();
        Map<Long, StockDashboardRiskOverlay> bySignal = stockDashboardRiskReader.findBySignals(
                tradeDate, horizon, signals, asOf);
        return rows.stream().map(row -> {
            StockDashboardRiskOverlay overlay = bySignal == null || row.signalId() == null ? null : bySignal.get(row.signalId());
            if (overlay == null) {
                StockDashboardRiskOverlay stockOverlay = safeOverlays.get(row.symbol());
                boolean legacy = row.strategyCode() == null || StockSignalDaily.LEGACY_STRATEGY_CODE.equals(row.strategyCode());
                overlay = stockOverlay == null ? null : legacy ? stockOverlay
                        : new StockDashboardRiskOverlay(stockOverlay.snapshot(), null);
            }
            return withRisk(row, overlay);
        }).toList();
    }

    private StockConsoleVo.SignalRow withRisk(
            StockConsoleVo.SignalRow row,
            StockDashboardRiskOverlay overlay
    ) {
        return new StockConsoleVo.SignalRow(
                row.symbol(), row.name(), row.price(), row.changePct(), row.signal(),
                row.bullishScore(), row.bearishScore(), row.riskScore(), row.confidence(),
                row.triggeredRuleCount(), row.suggestedPeriod(), row.updatedAt(),
                row.signalStatus(), row.quoteStatus(), row.signalDate(), row.quoteDate(),
                row.signalGeneratedAt(), row.signalFreshness(), row.generationType(),
                overlay == null ? null : overlay.snapshot(),
                overlay == null ? null : overlay.gateDecision(),
                row.signalId(), row.strategyCode(), row.strategyVersion(), row.strategyName());
    }

    private boolean isDirectionalSignal(String signal) {
        return SignalType.BULLISH.getCode().equals(signal)
                || SignalType.BEARISH.getCode().equals(signal)
                || SignalType.WATCH.getCode().equals(signal);
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
        if (StringUtils.hasText(query.signal())) {
            String comparable = SignalType.HIGH_RISK.getCode().equals(query.signal())
                    ? signal.getSignal()
                    : displaySignal(signal);
            if (!query.signal().equals(comparable)) {
                return false;
            }
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

    private String displaySignal(StockSignalDaily signal) {
        if (signal != null && isDirectionalSignal(signal.getSignalDirection())) {
            return signal.getSignalDirection();
        }
        return signal == null ? null : signal.getSignal();
    }

    private boolean isReadySignal(StockSignalDaily signal) {
        return signal != null
                && signal.getSignal() != null
                && SUPPORTED_SIGNALS.contains(signal.getSignal());
    }

    private boolean isCurrentSignal(
            StockSignalDaily signal,
            Map<String, StockDailyQuote> quotesBySymbol,
            LocalDate tradeDate
    ) {
        return isCurrentSignal(signal,
                quotesBySymbol.get(SymbolNormalizer.normalize(signal.getSymbol())), tradeDate);
    }

    private boolean isCurrentSignal(
            StockSignalDaily signal,
            StockDailyQuote quote,
            LocalDate tradeDate
    ) {
        return signal != null && tradeDate != null
                && tradeDate.equals(signal.getSignalDate())
                && completeRealQuote(quote)
                && tradeDate.equals(quote.getTradeDate());
    }

    private boolean completeRealQuote(StockDailyQuote quote) {
        return quote != null && quote.getClosePrice() != null
                && quote.getOpenPrice() != null && quote.getHighPrice() != null
                && quote.getLowPrice() != null && quote.getVolume() != null
                && quote.getVolume().compareTo(BigDecimal.ZERO) > 0
                && StringUtils.hasText(quote.getDataSource())
                && !"mock".equalsIgnoreCase(quote.getDataSource());
    }

    private List<StockConsoleVo.MetricCard> metrics(
            int candidateCount,
            List<StockSignalDaily> currentSignals,
            List<StockSignalDaily> historicalSignals,
            List<StockActualResult> actualResults
    ) {
        long bullish = countSignal(currentSignals, SignalType.BULLISH.getCode());
        long bearish = countSignal(currentSignals, SignalType.BEARISH.getCode());
        long watch = countSignal(currentSignals, SignalType.WATCH.getCode());
        long highRisk = countSignal(currentSignals, SignalType.HIGH_RISK.getCode());
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
                metric("产生信号", currentSignals.size(), "条", "cyan"),
                metric("历史信号", historicalSignals.size(), "条", "orange"),
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
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StockConsoleVo.SignalRow::strategyCode, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StockConsoleVo.SignalRow::strategyVersion, Comparator.nullsLast(Comparator.naturalOrder()));
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
        signals.stream().map(this::signalRecordUpdatedAt).filter(Objects::nonNull).forEach(updates::add);
        quotes.stream().map(StockDailyQuote::getSyncTime).filter(Objects::nonNull).forEach(updates::add);
        actualResults.stream().map(StockActualResult::getCreatedTime).filter(Objects::nonNull).forEach(updates::add);
        return updates.stream().max(Comparator.naturalOrder()).orElse(null);
    }

    private LocalDateTime signalGeneratedAt(StockSignalDaily signal) {
        return signal.getGeneratedAt();
    }

    private LocalDateTime signalRecordUpdatedAt(StockSignalDaily signal) {
        return signal.getGeneratedAt() != null ? signal.getGeneratedAt() : signal.getCreatedTime();
    }

    private static StockDailyQuote newerQuote(StockDailyQuote first, StockDailyQuote second) {
        int byTradeDate = compareNullable(first.getTradeDate(), second.getTradeDate());
        if (byTradeDate != 0) {
            return byTradeDate >= 0 ? first : second;
        }
        return compareNullable(first.getSyncTime(), second.getSyncTime()) >= 0 ? first : second;
    }

    private static StockSignalDaily newerSignal(StockSignalDaily first, StockSignalDaily second) {
        int byDate = compareNullable(first.getSignalDate(), second.getSignalDate());
        if (byDate != 0) {
            return byDate >= 0 ? first : second;
        }
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
        if (!StringUtils.hasText(triggeredRules)) {
            return 0;
        }
        try {
            JsonNode parsed = OBJECT_MAPPER.readTree(triggeredRules);
            if (parsed.isArray()) {
                int count = 0;
                for (JsonNode item : parsed) {
                    if ((item.isTextual() && StringUtils.hasText(item.asText()))
                            || (item.isObject() && StringUtils.hasText(item.path("rule_code").asText()))) {
                        count++;
                    }
                }
                return count;
            }
        } catch (Exception ignored) {
            // Historical rows may contain comma or whitespace separated codes.
        }
        return (int) java.util.Arrays.stream(triggeredRules.split("[,，\\s]+"))
                .filter(StringUtils::hasText)
                .count();
    }

    private LocalDateTime max(LocalDateTime first, LocalDateTime second) {
        return compareNullable(first, second) >= 0 ? first : second;
    }
}
