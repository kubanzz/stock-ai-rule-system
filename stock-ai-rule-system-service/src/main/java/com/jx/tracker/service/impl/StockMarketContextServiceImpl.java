package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jx.tracker.config.properties.StockDashboardProperties;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.enums.SignalType;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
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
import java.util.LinkedHashSet;
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

    private final StockDailyQuoteMapper stockDailyQuoteMapper;
    private final StockSignalDailyMapper stockSignalDailyMapper;
    private final MarketDataSyncRunMapper marketDataSyncRunMapper;
    private final StockDashboardProperties properties;

    @Override
    public StockConsoleVo.MarketContext marketContext(
            String market,
            LocalDate tradeDate,
            List<StockBase> candidates
    ) {
        String benchmarkSymbol = properties.benchmarkSymbol(market);
        if (tradeDate == null || candidates == null || candidates.isEmpty()) {
            return emptyContext(benchmarkSymbol);
        }
        List<String> candidateSymbols = candidates.stream()
                .map(StockBase::getSymbol)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (candidateSymbols.isEmpty()) {
            return emptyContext(benchmarkSymbol);
        }

        List<StockDailyQuote> benchmarkQuotes = benchmarkQuotes(benchmarkSymbol, tradeDate);
        List<StockConsoleVo.SparkPoint> trend = benchmarkQuotes.stream()
                .map(quote -> new StockConsoleVo.SparkPoint(
                        quote.getTradeDate().toString(), quote.getClosePrice()
                ))
                .toList();
        StockDailyQuote latestBenchmark = benchmarkQuotes.isEmpty() ? null : benchmarkQuotes.getLast();
        List<StockSignalDaily> recentSignals = recentSignals(candidateSymbols, tradeDate);

        return new StockConsoleVo.MarketContext(
                latestBenchmark != null,
                benchmarkSymbol,
                latestBenchmark == null ? null : latestBenchmark.getClosePrice(),
                latestBenchmark == null ? null : latestBenchmark.getChangePct(),
                latestBenchmark == null ? "unavailable" : marketStatus(latestBenchmark.getChangePct()),
                trend,
                industryStrength(candidates, candidateSymbols, tradeDate),
                sentiment(recentSignals),
                riskOverview(recentSignals, tradeDate)
        );
    }

    private List<StockDailyQuote> benchmarkQuotes(String benchmarkSymbol, LocalDate tradeDate) {
        if (!StringUtils.hasText(benchmarkSymbol)) {
            return List.of();
        }
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectList(new LambdaQueryWrapper<StockDailyQuote>()
                .eq(StockDailyQuote::getSymbol, benchmarkSymbol)
                .le(StockDailyQuote::getTradeDate, tradeDate)
                .isNotNull(StockDailyQuote::getClosePrice)
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last("LIMIT " + TREND_DAYS));
        return quotes.stream()
                .filter(quote -> benchmarkSymbol.equals(quote.getSymbol()))
                .filter(quote -> quote.getTradeDate() != null && !quote.getTradeDate().isAfter(tradeDate))
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

    private List<StockSignalDaily> recentSignals(List<String> candidateSymbols, LocalDate tradeDate) {
        List<StockSignalDaily> signals = stockSignalDailyMapper.selectList(new LambdaQueryWrapper<StockSignalDaily>()
                .in(StockSignalDaily::getSymbol, candidateSymbols)
                .le(StockSignalDaily::getSignalDate, tradeDate)
                .orderByDesc(StockSignalDaily::getSignalDate));
        Set<String> candidateSymbolSet = Set.copyOf(candidateSymbols);
        List<StockSignalDaily> scopedSignals = signals.stream()
                .filter(signal -> candidateSymbolSet.contains(signal.getSymbol()))
                .filter(signal -> signal.getSignalDate() != null && !signal.getSignalDate().isAfter(tradeDate))
                .sorted(Comparator.comparing(StockSignalDaily::getSignalDate).reversed())
                .toList();
        Set<LocalDate> recentDates = scopedSignals.stream()
                .map(StockSignalDaily::getSignalDate)
                .distinct()
                .limit(SENTIMENT_DAYS)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return scopedSignals.stream()
                .filter(signal -> recentDates.contains(signal.getSignalDate()))
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

    private StockConsoleVo.RiskOverview riskOverview(List<StockSignalDaily> recentSignals, LocalDate tradeDate) {
        List<StockSignalDaily> currentSignals = recentSignals.stream()
                .filter(signal -> Objects.equals(tradeDate, signal.getSignalDate()))
                .toList();
        long highRiskCount = countSignal(currentSignals, SignalType.HIGH_RISK.getCode());
        BigDecimal ratio = currentSignals.isEmpty()
                ? null
                : BigDecimal.valueOf(highRiskCount).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(currentSignals.size()), 2, RoundingMode.HALF_UP);
        MarketDataSyncRun latestSyncRun = marketDataSyncRunMapper.selectOne(
                new LambdaQueryWrapper<MarketDataSyncRun>()
                        .orderByDesc(MarketDataSyncRun::getStartedAt)
                        .orderByDesc(MarketDataSyncRun::getId)
                        .last("LIMIT 1")
        );
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

    private StockConsoleVo.MarketContext emptyContext(String benchmarkSymbol) {
        return new StockConsoleVo.MarketContext(
                false,
                benchmarkSymbol,
                null,
                null,
                "unavailable",
                List.of(),
                List.of(),
                new StockConsoleVo.Sentiment("信号情绪（7 日）·暂无数据", null, "unavailable"),
                new StockConsoleVo.RiskOverview(0, null, "unavailable", "unavailable", "暂无市场风险环境数据")
        );
    }
}
