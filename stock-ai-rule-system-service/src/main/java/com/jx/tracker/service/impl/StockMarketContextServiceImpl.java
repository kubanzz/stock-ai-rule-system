package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.config.properties.StockDashboardProperties;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.enums.MarketDataSyncType;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.TradeCalendarMapper;
import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import com.jx.tracker.service.StockMarketContextService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockMarketContextServiceImpl implements StockMarketContextService {

    private static final int TREND_DAYS = 20;
    private static final int SENTIMENT_DAYS = 7;
    private static final Set<String> VALID_SIGNAL_TYPES = Set.of(
            SignalType.BULLISH.getCode(),
            SignalType.BEARISH.getCode(),
            SignalType.WATCH.getCode(),
            SignalType.HIGH_RISK.getCode()
    );

    private final StockDailyQuoteMapper stockDailyQuoteMapper;
    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final MarketDataSyncRunMapper marketDataSyncRunMapper;
    private final TradeCalendarMapper tradeCalendarMapper;
    private final StockDashboardProperties properties;

    @Override
    public StockConsoleVo.MarketContext marketContext(
            StockConsoleVo.SignalDashboardQuery query,
            LocalDate tradeDate,
            List<StockBase> candidates
    ) {
        String benchmarkSymbol = properties.benchmarkSymbol(query.market());
        List<StockBase> scopedCandidates = candidates == null ? List.of() : candidates;
        List<String> candidateSymbols = scopedCandidates.stream()
                .map(StockBase::getSymbol)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        List<StockDailyQuote> benchmarkQuotes = benchmarkQuotes(benchmarkSymbol, tradeDate);
        List<StockConsoleVo.SparkPoint> trend = benchmarkQuotes.stream()
                .map(quote -> new StockConsoleVo.SparkPoint(
                        quote.getTradeDate().toString(), quote.getClosePrice()
                ))
                .toList();
        StockDailyQuote latestBenchmark = benchmarkQuotes.isEmpty() ? null : benchmarkQuotes.getLast();
        List<StockSignalDaily> recentSignals = tradeDate == null || candidateSymbols.isEmpty()
                ? List.of()
                : recentSignals(candidateSymbols, tradeDate, query);

        return new StockConsoleVo.MarketContext(
                latestBenchmark != null,
                benchmarkSymbol,
                latestBenchmark == null ? null : latestBenchmark.getClosePrice(),
                latestBenchmark == null ? null : latestBenchmark.getChangePct(),
                latestBenchmark == null ? "unavailable" : marketStatus(latestBenchmark.getChangePct()),
                trend,
                industryStrength(scopedCandidates, candidateSymbols, tradeDate),
                sentiment(recentSignals),
                riskOverview(recentSignals, tradeDate, benchmarkSymbol, candidateSymbols)
        );
    }

    private List<StockDailyQuote> benchmarkQuotes(String benchmarkSymbol, LocalDate tradeDate) {
        if (!StringUtils.hasText(benchmarkSymbol)) {
            return List.of();
        }
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getSymbol, benchmarkSymbol)
                .le(tradeDate != null, StockDailyQuote::getTradeDate, tradeDate)
                .isNotNull(StockDailyQuote::getClosePrice)
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last("LIMIT " + TREND_DAYS));
        return quotes.stream()
                .filter(quote -> benchmarkSymbol.equals(quote.getSymbol()))
                .filter(quote -> quote.getTradeDate() != null)
                .filter(quote -> tradeDate == null || !quote.getTradeDate().isAfter(tradeDate))
                .filter(quote -> quote.getClosePrice() != null)
                .sorted(Comparator.comparing(StockDailyQuote::getTradeDate).reversed())
                .limit(TREND_DAYS)
                .sorted(Comparator.comparing(StockDailyQuote::getTradeDate))
                .toList();
    }

    private List<StockConsoleVo.IndustryStrength> industryStrength(
            List<StockBase> candidates,
            List<String> candidateSymbols,
            LocalDate tradeDate
    ) {
        if (tradeDate == null || candidateSymbols.isEmpty()) {
            return List.of();
        }
        Map<String, StockBase> stocksBySymbol = candidates.stream()
                .filter(stock -> StringUtils.hasText(stock.getSymbol()))
                .collect(Collectors.toMap(
                        StockBase::getSymbol,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new
                ));
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getTradeDate, tradeDate)
                .in(StockDailyQuote::getSymbol, candidateSymbols)
                .isNotNull(StockDailyQuote::getChangePct));
        Map<String, List<BigDecimal>> changesByIndustry = quotes.stream()
                .filter(quote -> Objects.equals(tradeDate, quote.getTradeDate()))
                .filter(quote -> quote.getChangePct() != null)
                .filter(quote -> stocksBySymbol.containsKey(quote.getSymbol()))
                .filter(quote -> StringUtils.hasText(stocksBySymbol.get(quote.getSymbol()).getIndustry()))
                .collect(Collectors.groupingBy(
                        quote -> stocksBySymbol.get(quote.getSymbol()).getIndustry(),
                        LinkedHashMap::new,
                        Collectors.mapping(StockDailyQuote::getChangePct, Collectors.toList())
                ));
        List<StockConsoleVo.IndustryStrength> averages = changesByIndustry.entrySet().stream()
                .map(entry -> industryAverage(entry.getKey(), entry.getValue()))
                .toList();
        Comparator<StockConsoleVo.IndustryStrength> ascending = Comparator
                .comparing(StockConsoleVo.IndustryStrength::strength)
                .thenComparing(StockConsoleVo.IndustryStrength::industry);
        List<StockConsoleVo.IndustryStrength> strongest = averages.stream()
                .sorted(ascending.reversed())
                .limit(3)
                .toList();
        Set<String> selectedIndustries = strongest.stream()
                .map(StockConsoleVo.IndustryStrength::industry)
                .collect(Collectors.toSet());
        List<StockConsoleVo.IndustryStrength> result = new ArrayList<>(strongest);
        averages.stream()
                .filter(item -> !selectedIndustries.contains(item.industry()))
                .sorted(ascending)
                .limit(2)
                .forEach(result::add);
        return List.copyOf(result);
    }

    private StockConsoleVo.IndustryStrength industryAverage(String industry, List<BigDecimal> changes) {
        BigDecimal total = changes.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = total.divide(BigDecimal.valueOf(changes.size()), 2, RoundingMode.HALF_UP);
        String status = average.signum() > 0 ? "strong" : average.signum() < 0 ? "weak" : "flat";
        return new StockConsoleVo.IndustryStrength(industry, average, status);
    }

    private List<StockSignalDaily> recentSignals(
            List<String> candidateSymbols,
            LocalDate tradeDate,
            StockConsoleVo.SignalDashboardQuery query
    ) {
        List<LocalDate> tradingDates = recentTradingDates(query.market(), tradeDate);
        if (tradingDates.isEmpty()) {
            return List.of();
        }
        LocalDate earliestDate = tradingDates.getLast();
        LocalDate latestDate = tradingDates.getFirst();
        List<StockSignalDaily> signals = stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                .in(StockSignalDaily::getSymbol, candidateSymbols)
                .between(StockSignalDaily::getSignalDate, earliestDate, latestDate)
                .in(StockSignalDaily::getSignalDate, tradingDates)
                .eq(StringUtils.hasText(query.signal()), StockSignalDaily::getSignal, query.signal())
                .ge(query.confidenceMin() != null, StockSignalDaily::getConfidence, query.confidenceMin())
                .le(query.confidenceMax() != null, StockSignalDaily::getConfidence, query.confidenceMax())
                .orderByDesc(StockSignalDaily::getSignalDate));
        Set<String> candidateSymbolSet = Set.copyOf(candidateSymbols);
        Set<LocalDate> tradingDateSet = Set.copyOf(tradingDates);
        List<StockSignalDaily> scopedSignals = signals.stream()
                .filter(signal -> candidateSymbolSet.contains(signal.getSymbol()))
                .filter(signal -> tradingDateSet.contains(signal.getSignalDate()))
                .filter(signal -> signal.getSignal() != null && VALID_SIGNAL_TYPES.contains(signal.getSignal()))
                .filter(signal -> !StringUtils.hasText(query.signal()) || query.signal().equals(signal.getSignal()))
                .filter(signal -> query.confidenceMin() == null && query.confidenceMax() == null
                        || signal.getConfidence() != null)
                .filter(signal -> query.confidenceMin() == null
                        || signal.getConfidence().compareTo(query.confidenceMin()) >= 0)
                .filter(signal -> query.confidenceMax() == null
                        || signal.getConfidence().compareTo(query.confidenceMax()) <= 0)
                .sorted(Comparator.comparing(StockSignalDaily::getSignalDate).reversed())
                .toList();
        return scopedSignals;
    }

    private List<LocalDate> recentTradingDates(String market, LocalDate tradeDate) {
        List<String> marketAliases = MarketCodeNormalizer.aliases(market);
        List<TradeCalendar> calendars = tradeCalendarMapper.selectList(new LambdaQueryWrapper<TradeCalendar>()
                .in(TradeCalendar::getMarket, marketAliases)
                .eq(TradeCalendar::getOpen, true)
                .le(TradeCalendar::getTradeDate, tradeDate)
                .orderByDesc(TradeCalendar::getTradeDate)
                .last("LIMIT " + SENTIMENT_DAYS));
        return calendars.stream()
                .filter(calendar -> MarketCodeNormalizer.equivalent(market, calendar.getMarket()))
                .filter(calendar -> Boolean.TRUE.equals(calendar.getOpen()))
                .map(TradeCalendar::getTradeDate)
                .filter(Objects::nonNull)
                .filter(date -> !date.isAfter(tradeDate))
                .distinct()
                .sorted(Comparator.reverseOrder())
                .limit(SENTIMENT_DAYS)
                .toList();
    }

    private StockConsoleVo.Sentiment sentiment(List<StockSignalDaily> signals) {
        if (signals.isEmpty()) {
            return new StockConsoleVo.Sentiment("信号情绪（7 日）·暂无数据", null, "unavailable");
        }
        long bullish = countSignal(signals, SignalType.BULLISH.getCode());
        long watch = countSignal(signals, SignalType.WATCH.getCode());
        long highRisk = countSignal(signals, SignalType.HIGH_RISK.getCode());
        BigDecimal weighted = BigDecimal.valueOf(bullish * 100L + watch * 50L + highRisk * 25L);
        BigDecimal score = weighted.divide(BigDecimal.valueOf(signals.size()), 2, RoundingMode.HALF_UP);
        String status = score.compareTo(BigDecimal.valueOf(60)) >= 0
                ? "bullish"
                : score.compareTo(BigDecimal.valueOf(40)) <= 0 ? "bearish" : "balanced";
        String label = switch (status) {
            case "bullish" -> "信号情绪（7 日）·偏多";
            case "bearish" -> "信号情绪（7 日）·偏空";
            default -> "信号情绪（7 日）·均衡";
        };
        return new StockConsoleVo.Sentiment(label, score, status);
    }

    private StockConsoleVo.RiskOverview riskOverview(
            List<StockSignalDaily> recentSignals,
            LocalDate tradeDate,
            String benchmarkSymbol,
            List<String> candidateSymbols
    ) {
        List<StockSignalDaily> currentSignals = recentSignals.stream()
                .filter(signal -> Objects.equals(tradeDate, signal.getSignalDate()))
                .toList();
        long highRiskCount = countSignal(currentSignals, SignalType.HIGH_RISK.getCode());
        BigDecimal ratio = currentSignals.isEmpty()
                ? null
                : BigDecimal.valueOf(highRiskCount).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(currentSignals.size()), 2, RoundingMode.HALF_UP);
        List<String> targetSymbols = new ArrayList<>(candidateSymbols);
        if (StringUtils.hasText(benchmarkSymbol)) {
            targetSymbols.add(benchmarkSymbol);
        }
        LambdaQueryWrapper<MarketDataSyncRun> syncQuery = new LambdaQueryWrapper<MarketDataSyncRun>()
                .eq(MarketDataSyncRun::getSyncType, MarketDataSyncType.DAILY_QUOTE.getCode())
                .le(tradeDate != null, MarketDataSyncRun::getEndDate, tradeDate)
                .and(wrapper -> {
                    wrapper.isNull(MarketDataSyncRun::getTargetSymbol);
                    if (!targetSymbols.isEmpty()) {
                        wrapper.or().in(MarketDataSyncRun::getTargetSymbol, targetSymbols);
                    }
                })
                .orderByDesc(MarketDataSyncRun::getStartedAt)
                .orderByDesc(MarketDataSyncRun::getId)
                .last("LIMIT 1");
        MarketDataSyncRun latestSyncRun = marketDataSyncRunMapper.selectOne(syncQuery);
        String syncStatus = latestSyncRun == null || !StringUtils.hasText(latestSyncRun.getStatus())
                ? "unavailable"
                : latestSyncRun.getStatus();
        if (ratio == null) {
            return new StockConsoleVo.RiskOverview(
                    0, null, syncStatus, "unavailable", "选定交易日暂无信号；最近同步状态：" + syncStatus
            );
        }
        String level = ratio.compareTo(BigDecimal.valueOf(20)) >= 0
                ? "high"
                : ratio.compareTo(BigDecimal.valueOf(10)) >= 0 ? "medium" : "low";
        String summary = "高风险信号 %d 条，占比 %s%%；最近同步状态：%s"
                .formatted(highRiskCount, ratio.toPlainString(), syncStatus);
        return new StockConsoleVo.RiskOverview(highRiskCount, ratio, syncStatus, level, summary);
    }

    private long countSignal(List<StockSignalDaily> signals, String signalType) {
        return signals.stream().filter(signal -> signalType.equals(signal.getSignal())).count();
    }

    private String marketStatus(BigDecimal changePct) {
        if (changePct == null) {
            return "平稳";
        }
        return changePct.signum() > 0 ? "偏强" : changePct.signum() < 0 ? "偏弱" : "平稳";
    }

}
