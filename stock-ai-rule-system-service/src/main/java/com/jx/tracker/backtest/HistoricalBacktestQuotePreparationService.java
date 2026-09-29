package com.jx.tracker.backtest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MarketDataProviderSelection;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Fetch missing real daily quotes before the transactional backtest starts. */
@Service
public class HistoricalBacktestQuotePreparationService {

    private static final int MAX_ON_DEMAND_SYMBOLS = 20;
    private static final int MAX_ON_DEMAND_YEARS = 3;
    private static final int FACTOR_WARMUP_DAYS = 90;
    private static final int REQUIRED_FACTOR_QUOTES = 26;

    private final StockDailyQuoteMapper quoteMapper;
    private final StockWatchlistMapper watchlistMapper;
    private final StockWatchlistItemMapper watchlistItemMapper;
    private final MarketDataProviderResolver providerResolver;
    private final MarketDataSyncService syncService;

    public HistoricalBacktestQuotePreparationService(
            StockDailyQuoteMapper quoteMapper,
            StockWatchlistMapper watchlistMapper,
            StockWatchlistItemMapper watchlistItemMapper,
            MarketDataProviderResolver providerResolver,
            MarketDataSyncService syncService) {
        this.quoteMapper = quoteMapper;
        this.watchlistMapper = watchlistMapper;
        this.watchlistItemMapper = watchlistItemMapper;
        this.providerResolver = providerResolver;
        this.syncService = syncService;
    }

    public void prepare(BacktestRequestDto request) {
        // Let the backtest service report invalid request fields and empty pools.
        if (request == null || request.getStartDate() == null || request.getEndDate() == null
                || request.getEndDate().isBefore(request.getStartDate())
                || request.getEndDate().isAfter(LocalDate.now())
                || (!"rule".equals(request.getObjectType())
                    && !"candidate_rule".equals(request.getObjectType()))
                || !StringUtils.hasText(request.getObjectCode())) {
            return;
        }
        Set<String> symbols = selectedSmallPool(request);
        if (symbols.isEmpty()) {
            return;
        }

        LocalDate warmupStart = request.getStartDate().minusDays(FACTOR_WARMUP_DAYS);
        LocalDate tailEnd = tailEnd(request);
        List<StockDailyQuote> localQuotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .in(StockDailyQuote::getSymbol, symbols)
                .ge(StockDailyQuote::getTradeDate, warmupStart)
                .le(StockDailyQuote::getTradeDate, tailEnd));
        Map<String, List<StockDailyQuote>> bySymbol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            bySymbol.put(symbol, new ArrayList<>());
        }
        if (localQuotes != null) {
            for (StockDailyQuote quote : localQuotes) {
                if (quote != null && bySymbol.containsKey(quote.getSymbol()) && usable(quote)) {
                    bySymbol.get(quote.getSymbol()).add(quote);
                }
            }
        }

        List<String> missing = symbols.stream()
                .filter(symbol -> needsSync(bySymbol.get(symbol), request, tailEnd))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        if (request.getStartDate().isBefore(request.getEndDate().minusYears(MAX_ON_DEMAND_YEARS))) {
            throw new ServiceException("历史行情不足，当前按需补齐最多支持 3 年回测区间。请缩短区间或先同步历史日线。", 400);
        }

        MarketDataProviderSelection selection = providerResolver.resolve();
        if (selection.fallback() || !Set.of("aktools/akshare", "tushare").contains(selection.dataSource())) {
            throw new ServiceException("历史行情不足，当前未配置可用的真实行情源。请启用 AKTools 或 Tushare 后重试。", 400);
        }
        for (String symbol : missing) {
            if (!symbol.matches("\\d{6}\\.(SZ|SH|BJ)")) {
                throw new ServiceException("股票 " + symbol + " 缺少历史行情，当前自动补齐仅支持 A 股日线。请先导入该股票的真实历史日线。", 400);
            }
            DailyQuoteSyncRequestDto syncRequest = new DailyQuoteSyncRequestDto();
            syncRequest.setTargetSymbol(symbol);
            syncRequest.setStartDate(warmupStart);
            syncRequest.setEndDate(tailEnd);
            syncRequest.setTriggerType("backtest");
            syncRequest.setTriggerBy("single_rule_backtest");
            MarketDataSyncResultDto result = syncService.syncDailyQuotes(syncRequest);
            if (result == null || !"success".equals(result.getStatus())
                    || !selection.dataSource().equals(result.getDataSource())) {
                String detail = result == null || result.getErrors() == null || result.getErrors().isEmpty()
                        ? "行情同步失败" : result.getErrors().getFirst();
                throw new ServiceException("股票 " + symbol + " 的真实历史行情补齐失败：" + detail, 400);
            }
            List<StockDailyQuote> refreshed = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                    .eq(StockDailyQuote::getSymbol, symbol)
                    .ge(StockDailyQuote::getTradeDate, warmupStart)
                    .le(StockDailyQuote::getTradeDate, tailEnd));
            List<StockDailyQuote> usableRefreshed = refreshed == null ? List.of() : refreshed.stream()
                    .filter(quote -> quote != null && usable(quote)).toList();
            long usableThroughEnd = usableRefreshed.stream()
                    .filter(quote -> !quote.getTradeDate().isAfter(request.getEndDate())).count();
            if (usableThroughEnd < REQUIRED_FACTOR_QUOTES) {
                throw new ServiceException("股票 " + symbol + " 已同步历史日线，但截至回测结束日只有 "
                        + usableThroughEnd + " 条完整行情，计算技术因子至少需要 26 条。请调整回测区间或股票池。", 400);
            }
            if (needsSync(usableRefreshed, request, tailEnd)) {
                throw new ServiceException("股票 " + symbol + " 的真实行情源未返回足够覆盖所选回测区间的数据。"
                        + "已同步的交易日仍有大段缺口，请缩短区间或先补齐历史日线。", 400);
            }
            if (result.getInserted() > 0 || result.getUpdated() > 0) {
                request.setForceFactorRecalculation(true);
            }
        }
    }

    private Set<String> selectedSmallPool(BacktestRequestDto request) {
        String poolType = request.getStockPoolType();
        if ("custom".equalsIgnoreCase(poolType)
                || (!StringUtils.hasText(poolType) && request.getSymbols() != null && !request.getSymbols().isEmpty())) {
            return boundedSymbols(request.getSymbols(), request);
        }
        if (!"watchlist".equalsIgnoreCase(poolType)) {
            // Broad market runs use the locally imported universe. Their report
            // diagnoses missing history without launching thousands of requests.
            return Set.of();
        }
        String poolCode = StringUtils.hasText(request.getPoolCode()) ? request.getPoolCode() : "my-follow";
        StockWatchlist pool = watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, poolCode).last("LIMIT 1"));
        if (pool == null) {
            return Set.of();
        }
        return boundedSymbols(watchlistItemMapper.selectList(Wrappers.<StockWatchlistItem>lambdaQuery()
                        .eq(StockWatchlistItem::getWatchlistId, pool.getId()))
                .stream().map(StockWatchlistItem::getSymbol).toList(), request);
    }

    private Set<String> boundedSymbols(List<String> rawSymbols, BacktestRequestDto request) {
        if (rawSymbols == null) {
            return Set.of();
        }
        Set<String> symbols = rawSymbols.stream()
                .filter(StringUtils::hasText)
                .map(SymbolNormalizer::normalize)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (symbols.size() <= MAX_ON_DEMAND_SYMBOLS) {
            return symbols;
        }
        // Keep already populated large pools usable. Only an empty large pool
        // needs an explicit request to narrow scope before remote backfilling.
        List<StockDailyQuote> existing = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .in(StockDailyQuote::getSymbol, symbols)
                .ge(StockDailyQuote::getTradeDate, request.getStartDate())
                .le(StockDailyQuote::getTradeDate, request.getEndDate())
                .last("LIMIT 1"));
        if (existing == null || existing.isEmpty()) {
            throw new ServiceException("股票池超过 20 只且没有本地历史行情。请缩小股票池到 20 只以内，或先批量同步真实历史日线。", 400);
        }
        return Set.of();
    }

    private LocalDate tailEnd(BacktestRequestDto request) {
        int holdingDays = request.getHoldingPeriod() == null ? 1 : Math.max(1, request.getHoldingPeriod());
        long calendarDays = Math.min(252, holdingDays) * 2L + 14;
        LocalDate candidate = request.getEndDate().plusDays(calendarDays);
        return candidate.isAfter(LocalDate.now()) ? LocalDate.now() : candidate;
    }

    private boolean needsSync(List<StockDailyQuote> quotes, BacktestRequestDto request, LocalDate tailEnd) {
        LocalDate start = request.getStartDate();
        LocalDate end = request.getEndDate();
        long warmup = quotes.stream().filter(quote -> quote.getTradeDate().isBefore(start)).count();
        long inside = quotes.stream().filter(quote -> !quote.getTradeDate().isBefore(start)
                && !quote.getTradeDate().isAfter(end)).count();
        long tail = quotes.stream().filter(quote -> quote.getTradeDate().isAfter(end)
                && !quote.getTradeDate().isAfter(tailEnd)).count();
        long expectedWeekdays = weekdays(start, end);
        boolean sparseInterval = inside < Math.max(1L, (expectedWeekdays * 3L) / 4L);
        List<LocalDate> insideDates = quotes.stream()
                .map(StockDailyQuote::getTradeDate)
                .filter(day -> !day.isBefore(start) && !day.isAfter(end))
                .sorted()
                .toList();
        if (!insideDates.isEmpty()) {
            sparseInterval |= insideDates.getFirst().isAfter(start.plusDays(20))
                    || insideDates.getLast().isBefore(end.minusDays(20));
            for (int index = 1; index < insideDates.size(); index++) {
                if (insideDates.get(index).isAfter(insideDates.get(index - 1).plusDays(20))) {
                    sparseInterval = true;
                    break;
                }
            }
        }
        // Recent backtests cannot have quotes for trading days that have not
        // happened yet. Give the provider time to publish the holding horizon.
        int holdingDays = request.getHoldingPeriod() == null ? 1 : Math.max(1, request.getHoldingPeriod());
        boolean holdingMatured = weekdays(end.plusDays(1), LocalDate.now()) >= holdingDays + 2L;
        boolean missingTail = holdingMatured && tailEnd.isAfter(end) && tail < holdingDays;
        return warmup < REQUIRED_FACTOR_QUOTES || sparseInterval || missingTail;
    }

    private long weekdays(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            return 0;
        }
        return start.datesUntil(end.plusDays(1))
                .filter(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
                        && day.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();
    }

    private boolean usable(StockDailyQuote quote) {
        return quote.getTradeDate() != null && quote.getClosePrice() != null && quote.getVolume() != null
                && quote.getVolume().compareTo(BigDecimal.ZERO) > 0
                && !"mock".equalsIgnoreCase(quote.getDataSource());
    }
}
