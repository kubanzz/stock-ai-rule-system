package com.jx.tracker.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.entity.SignalBackfillRun;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.enums.MarketDataSyncStatus;
import com.jx.tracker.domain.vo.SignalBackfillRunVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.mapper.SignalBackfillRunMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import com.jx.tracker.signal.service.StockSignalService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** Fetches missing open days for the bound application pool, or the legacy "my-follow" pool. */
@Service
public class SignalBackfillService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final LocalTime AFTER_CLOSE = LocalTime.of(15, 30);
    private static final int CALENDAR_PAGE_SIZE = 500;
    private static final int FACTOR_HISTORY_SIZE = 26;

    private final MarketDataProviderResolver providerResolver;
    private final MarketDataSyncService marketDataSyncService;
    private final TradeCalendarService tradeCalendarService;
    private final StockWatchlistMapper watchlistMapper;
    private final StockWatchlistItemMapper watchlistItemMapper;
    private final StockDailyQuoteMapper quoteMapper;
    private final StockSignalDailyMapper signalMapper;
    private final IStockFactorDailyService factorService;
    private final StockSignalService signalService;
    private final SignalBackfillRunMapper runMapper;
    private final ObjectMapper objectMapper;
    private final TaskExecutor taskExecutor;
    private final Clock clock;
    private final ResearchReboundApplicationScope reboundScope;
    private String activeRunId;

    public SignalBackfillService(
            MarketDataProviderResolver providerResolver,
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper watchlistMapper,
            StockWatchlistItemMapper watchlistItemMapper,
            StockDailyQuoteMapper quoteMapper,
            StockSignalDailyMapper signalMapper,
            IStockFactorDailyService factorService,
            StockSignalService signalService,
            SignalBackfillRunMapper runMapper,
            ObjectMapper objectMapper,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this(providerResolver, marketDataSyncService, tradeCalendarService, watchlistMapper,
                watchlistItemMapper, quoteMapper, signalMapper, factorService, signalService,
                runMapper, objectMapper, taskExecutor, Clock.system(SHANGHAI));
    }

    @Autowired
    public SignalBackfillService(
            MarketDataProviderResolver providerResolver,
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper watchlistMapper,
            StockWatchlistItemMapper watchlistItemMapper,
            StockDailyQuoteMapper quoteMapper,
            StockSignalDailyMapper signalMapper,
            IStockFactorDailyService factorService,
            StockSignalService signalService,
            SignalBackfillRunMapper runMapper,
            ObjectMapper objectMapper,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
            ResearchReboundApplicationScope reboundScope) {
        this(providerResolver, marketDataSyncService, tradeCalendarService, watchlistMapper,
                watchlistItemMapper, quoteMapper, signalMapper, factorService, signalService,
                runMapper, objectMapper, taskExecutor, Clock.system(SHANGHAI), reboundScope);
    }

    SignalBackfillService(
            MarketDataProviderResolver providerResolver,
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper watchlistMapper,
            StockWatchlistItemMapper watchlistItemMapper,
            StockDailyQuoteMapper quoteMapper,
            StockSignalDailyMapper signalMapper,
            IStockFactorDailyService factorService,
            StockSignalService signalService,
            SignalBackfillRunMapper runMapper,
            ObjectMapper objectMapper,
            TaskExecutor taskExecutor,
            Clock clock) {
        this(providerResolver, marketDataSyncService, tradeCalendarService, watchlistMapper,
                watchlistItemMapper, quoteMapper, signalMapper, factorService, signalService,
                runMapper, objectMapper, taskExecutor, clock, null);
    }

    SignalBackfillService(
            MarketDataProviderResolver providerResolver,
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper watchlistMapper,
            StockWatchlistItemMapper watchlistItemMapper,
            StockDailyQuoteMapper quoteMapper,
            StockSignalDailyMapper signalMapper,
            IStockFactorDailyService factorService,
            StockSignalService signalService,
            SignalBackfillRunMapper runMapper,
            ObjectMapper objectMapper,
            TaskExecutor taskExecutor,
            Clock clock,
            ResearchReboundApplicationScope reboundScope) {
        this.reboundScope = reboundScope;
        this.providerResolver = providerResolver;
        this.marketDataSyncService = marketDataSyncService;
        this.tradeCalendarService = tradeCalendarService;
        this.watchlistMapper = watchlistMapper;
        this.watchlistItemMapper = watchlistItemMapper;
        this.quoteMapper = quoteMapper;
        this.signalMapper = signalMapper;
        this.factorService = factorService;
        this.signalService = signalService;
        this.runMapper = runMapper;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markInterruptedRuns() {
        List<SignalBackfillRun> unfinished = runMapper.selectList(Wrappers.<SignalBackfillRun>lambdaQuery()
                .in(SignalBackfillRun::getStatus, "QUEUED", "RUNNING"));
        for (SignalBackfillRun run : unfinished) {
            SignalBackfillRunVo previous = parse(run);
            List<SignalBackfillRunVo.Failure> failures = new ArrayList<>(previous.failures());
            failures.add(new SignalBackfillRunVo.Failure("", null, "RESTART", "服务重启，任务中断"));
            save(new SignalBackfillRunVo(previous.runId(), "FAILED", "COMPLETED",
                    previous.startedAt(), LocalDateTime.now(clock), previous.latestCompletedTradeDate(),
                    previous.totalSymbols(), previous.totalDates(), previous.totalTasks(),
                    previous.completedTasks(), previous.syncedQuotes(), previous.calculatedFactors(),
                    previous.generatedSignals(), previous.backfilledSignalCount(),
                    previous.missingQuotes(), failures), false);
        }
    }

    public synchronized SignalBackfillRunVo start() {
        // Never allow a mock fallback to write plausible-looking production quotes or signals.
        var provider = providerResolver.resolve();
        if (provider == null || "mock".equalsIgnoreCase(provider.dataSource())) {
            throw new ServiceException("当前行情数据源为模拟数据，无法执行真实行情补齐");
        }
        if (activeRunId != null) {
            SignalBackfillRunVo active = get(activeRunId);
            if (active != null && ("QUEUED".equals(active.status()) || "RUNNING".equals(active.status()))) {
                return active;
            }
            activeRunId = null;
        }
        SignalBackfillRunVo queued = new SignalBackfillRunVo(UUID.randomUUID().toString(),
                "QUEUED", "QUEUED", LocalDateTime.now(clock), null, null,
                0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        save(queued, true);
        activeRunId = queued.runId();
        try {
            taskExecutor.execute(() -> run(queued, provider.dataSource()));
        } catch (RuntimeException ex) {
            fail(new Progress(queued), "QUEUED", ex);
            activeRunId = null;
            throw ex;
        }
        return queued;
    }

    public SignalBackfillRunVo get(String runId) {
        SignalBackfillRun run = runId == null ? null : runMapper.selectById(runId);
        return run == null ? null : parse(run);
    }

    public SignalBackfillRunVo latest() {
        SignalBackfillRun run = runMapper.selectOne(Wrappers.<SignalBackfillRun>lambdaQuery()
                .orderByDesc(SignalBackfillRun::getStartedAt)
                .last("LIMIT 1"));
        return run == null ? null : parse(run);
    }

    private void run(SignalBackfillRunVo queued, String dataSource) {
        Progress progress = new Progress(queued);
        boolean refreshLegacyTushare = "tushare".equalsIgnoreCase(dataSource);
        try {
            progress.status = "RUNNING";
            progress.stage = "CALENDAR";
            publish(progress);

            boolean boundPool = reboundScope != null && reboundScope.hasBoundStockPool();
            Set<String> applicationSymbols = new LinkedHashSet<>(reboundScope == null
                    ? Set.of() : reboundScope.activeApplicationSymbols());
            if (!boundPool) applicationSymbols.addAll(watchedSymbols(progress));
            List<String> symbols = aShareSymbols(applicationSymbols, progress);
            Set<String> reboundSymbols = new LinkedHashSet<>(
                    reboundScope == null ? Set.of() : reboundScope.activeSymbols());
            reboundSymbols.retainAll(symbols);
            progress.totalSymbols = symbols.size();
            if (symbols.isEmpty()) {
                throw new ServiceException(boundPool
                        ? "当前应用方案绑定的股票分组快照中没有可补齐的 A 股股票，已停止任务"
                        : "“我的关注”中没有可补齐的 A 股股票");
            }
            LocalDate cutoff = completedCutoff();
            Map<String, LocalDate> starts = new LinkedHashMap<>();
            Map<String, Boolean> warmingUp = new HashMap<>();
            Map<String, LocalDate> earliestKnownDate = new HashMap<>();
            for (String symbol : symbols) {
                StockDailyQuote latest = quoteMapper.selectOne(Wrappers.<StockDailyQuote>lambdaQuery()
                        .eq(StockDailyQuote::getSymbol, symbol)
                        .isNotNull(StockDailyQuote::getDataSource)
                        .ne(StockDailyQuote::getDataSource, "mock")
                        .isNotNull(StockDailyQuote::getOpenPrice)
                        .isNotNull(StockDailyQuote::getHighPrice)
                        .isNotNull(StockDailyQuote::getLowPrice)
                        .isNotNull(StockDailyQuote::getClosePrice)
                        .isNotNull(StockDailyQuote::getVolume)
                        .gt(StockDailyQuote::getVolume, BigDecimal.ZERO)
                        .le(StockDailyQuote::getTradeDate, cutoff)
                        .orderByDesc(StockDailyQuote::getTradeDate)
                        .last("LIMIT 1"));
                long historyCount = quoteMapper.selectCount(Wrappers.<StockDailyQuote>lambdaQuery()
                        .eq(StockDailyQuote::getSymbol, symbol)
                        .isNotNull(StockDailyQuote::getDataSource)
                        .ne(StockDailyQuote::getDataSource, "mock")
                        .isNotNull(StockDailyQuote::getOpenPrice)
                        .isNotNull(StockDailyQuote::getHighPrice)
                        .isNotNull(StockDailyQuote::getLowPrice)
                        .isNotNull(StockDailyQuote::getClosePrice)
                        .isNotNull(StockDailyQuote::getVolume)
                        .gt(StockDailyQuote::getVolume, BigDecimal.ZERO)
                        .le(StockDailyQuote::getTradeDate, cutoff));
                StockDailyQuote earliest = quoteMapper.selectOne(Wrappers.<StockDailyQuote>lambdaQuery()
                        .eq(StockDailyQuote::getSymbol, symbol)
                        .isNotNull(StockDailyQuote::getDataSource)
                        .ne(StockDailyQuote::getDataSource, "mock")
                        .isNotNull(StockDailyQuote::getOpenPrice)
                        .isNotNull(StockDailyQuote::getHighPrice)
                        .isNotNull(StockDailyQuote::getLowPrice)
                        .isNotNull(StockDailyQuote::getClosePrice)
                        .isNotNull(StockDailyQuote::getVolume)
                        .gt(StockDailyQuote::getVolume, BigDecimal.ZERO)
                        .le(StockDailyQuote::getTradeDate, cutoff)
                        .orderByAsc(StockDailyQuote::getTradeDate)
                        .last("LIMIT 1"));
                earliestKnownDate.put(symbol, earliest == null ? null : earliest.getTradeDate());
                // Last valid quote is the primary catch-up cursor. A bounded lookback also detects
                // isolated gaps immediately before it without replaying years of old history or
                // requiring quotes from before the first known row.
                LocalDate start = latest == null || latest.getTradeDate() == null
                        ? cutoff.minusDays(90) : latest.getTradeDate().minusDays(60);
                if (historyCount < FACTOR_HISTORY_SIZE) {
                    start = cutoff.minusDays(90);
                    warmingUp.put(symbol, true);
                } else if (earliest != null && earliest.getTradeDate() != null
                        && earliest.getTradeDate().isAfter(start)) {
                    start = earliest.getTradeDate();
                }
                if (reboundSymbols.contains(symbol)) {
                    LocalDate requiredStart = reboundScope.historyStart(symbol, cutoff);
                    if (requiredStart.isBefore(cutoff.minusDays(60))) {
                        start = requiredStart;
                        warmingUp.put(symbol, true);
                    }
                }
                starts.put(symbol, start.isAfter(cutoff) ? cutoff : start);
            }
            LocalDate calendarStart = starts.values().stream().min(Comparator.naturalOrder()).orElse(cutoff);
            syncCalendar(calendarStart, cutoff, queued.runId());
            List<LocalDate> dates = openDates(calendarStart, cutoff);
            if (dates.isEmpty()) {
                throw new ServiceException("交易日历中没有可用交易日");
            }
            // A successful transport may still return a stale or partial calendar.
            // Do not report a completed catch-up when recent sessions are unknown.
            boolean hasUnconfirmedWeekday = dates.getLast().isBefore(cutoff)
                    && dates.getLast().plusDays(1).datesUntil(cutoff.plusDays(1))
                        .anyMatch(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY
                                && day.getDayOfWeek() != DayOfWeek.SUNDAY
                                && tradeCalendarService.getByMarketAndTradeDate("CN", day) == null);
            if (hasUnconfirmedWeekday) {
                progress.failures.add(new SignalBackfillRunVo.Failure("", cutoff,
                        "CALENDAR", "交易日历缺少最近工作日记录，已确认交易日仅到 " + dates.getLast()));
            }
            progress.totalDates = dates.size();
            progress.totalTasks = (int) starts.entrySet().stream()
                    .mapToLong(entry -> dates.stream().filter(date -> !date.isBefore(entry.getValue())).count())
                    .sum();
            progress.stage = "SCANNING";
            publish(progress);

            Map<String, List<LocalDate>> gapsBySymbol = scanGaps(symbols, starts, dates,
                    refreshLegacyTushare);
            LocalDate latestOpenDate = dates.getLast();
            Map<String, Boolean> latestRefreshSucceeded = new HashMap<>();
            boolean benchmarkRefreshed = reboundSymbols.isEmpty()
                    || syncReboundBenchmark(calendarStart, latestOpenDate, queued.runId(), progress);
            progress.stage = "SYNCING_QUOTES";
            publish(progress);
            for (String symbol : symbols) {
                List<LocalDate> syncDates = new ArrayList<>(gapsBySymbol.getOrDefault(symbol, List.of()));
                if (reboundSymbols.contains(symbol)) {
                    // QFQ can revise earlier prices after a corporate action. Refresh
                    // the full short factor window together, not only today's bar.
                    Set<LocalDate> refreshWindow = new LinkedHashSet<>(syncDates);
                    dates.stream().filter(date -> !date.isBefore(latestOpenDate.minusDays(60)))
                            .forEach(refreshWindow::add);
                    syncDates = new ArrayList<>(refreshWindow);
                    syncDates.sort(Comparator.naturalOrder());
                }
                // The latest session may contain a valid-looking intraday snapshot.
                // Always refresh its final daily bar after market close.
                if (!syncDates.contains(latestOpenDate)) {
                    syncDates.add(latestOpenDate);
                    syncDates.sort(Comparator.naturalOrder());
                }
                StockDailyQuote beforeLatestRefresh = quoteMapper.selectOne(Wrappers.<StockDailyQuote>lambdaQuery()
                        .eq(StockDailyQuote::getSymbol, symbol)
                        .eq(StockDailyQuote::getTradeDate, latestOpenDate)
                        .last("LIMIT 1"));
                LocalDateTime previousSyncTime = beforeLatestRefresh == null
                        ? null : beforeLatestRefresh.getSyncTime();
                for (List<LocalDate> interval : contiguousGaps(syncDates, dates)) {
                    DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
                    request.setTargetSymbol(symbol);
                    request.setStartDate(interval.getFirst());
                    request.setEndDate(interval.getLast());
                    request.setTriggerType("signal-backfill");
                    request.setTriggerBy(queued.runId());
                    try {
                        MarketDataSyncResultDto result = marketDataSyncService.syncDailyQuotes(request);
                        if (result == null || !MarketDataSyncStatus.SUCCESS.getCode().equals(result.getStatus())
                                || result.getFailed() != null && result.getFailed() > 0
                                || result.getScanned() != null && result.getScanned() == 0) {
                            progress.failures.add(new SignalBackfillRunVo.Failure(symbol, interval.getFirst(),
                                    "SYNCING_QUOTES", syncError(result)));
                            if (interval.contains(latestOpenDate)) {
                                latestRefreshSucceeded.put(symbol, false);
                            }
                        } else if (interval.contains(latestOpenDate)) {
                            StockDailyQuote afterLatestRefresh = quoteMapper.selectOne(Wrappers.<StockDailyQuote>lambdaQuery()
                                    .eq(StockDailyQuote::getSymbol, symbol)
                                    .eq(StockDailyQuote::getTradeDate, latestOpenDate)
                                    .last("LIMIT 1"));
                            boolean refreshed = completeQuote(afterLatestRefresh)
                                    && afterLatestRefresh.getSyncTime() != null
                                    && (previousSyncTime == null
                                        || afterLatestRefresh.getSyncTime().isAfter(previousSyncTime));
                            latestRefreshSucceeded.put(symbol, refreshed);
                            if (!refreshed) {
                                progress.failures.add(new SignalBackfillRunVo.Failure(symbol, latestOpenDate,
                                        "SYNCING_QUOTES", "最新交易日行情未确认刷新，保留原信号"));
                            }
                        }
                    } catch (RuntimeException ex) {
                        progress.failures.add(new SignalBackfillRunVo.Failure(symbol, interval.getFirst(),
                                "SYNCING_QUOTES", clean(ex)));
                        if (interval.contains(latestOpenDate)) {
                            latestRefreshSucceeded.put(symbol, false);
                        }
                    }
                }
                publish(progress);
            }

            for (String symbol : symbols) {
                if (!Boolean.TRUE.equals(warmingUp.get(symbol))) {
                    continue;
                }
                StockDailyQuote firstAvailable = quoteMapper.selectOne(Wrappers.<StockDailyQuote>lambdaQuery()
                        .eq(StockDailyQuote::getSymbol, symbol)
                        .isNotNull(StockDailyQuote::getDataSource)
                        .ne(StockDailyQuote::getDataSource, "mock")
                        .isNotNull(StockDailyQuote::getOpenPrice)
                        .isNotNull(StockDailyQuote::getHighPrice)
                        .isNotNull(StockDailyQuote::getLowPrice)
                        .isNotNull(StockDailyQuote::getClosePrice)
                        .isNotNull(StockDailyQuote::getVolume)
                        .gt(StockDailyQuote::getVolume, BigDecimal.ZERO)
                        .ge(StockDailyQuote::getTradeDate, starts.get(symbol))
                        .le(StockDailyQuote::getTradeDate, cutoff)
                        .orderByAsc(StockDailyQuote::getTradeDate)
                        .last("LIMIT 1"));
                earliestKnownDate.put(symbol, firstAvailable == null ? null : firstAvailable.getTradeDate());
            }

            progress.stage = "CALCULATING";
            publish(progress);
            Map<String, Boolean> blockedByGap = new HashMap<>();
            Map<String, Integer> completeBars = new HashMap<>();
            for (LocalDate date : dates) {
                boolean allQuotesComplete = true;
                for (String symbol : symbols) {
                    if (date.isBefore(starts.get(symbol))) {
                        continue;
                    }
                    StockDailyQuote quote = quoteMapper.selectOne(Wrappers.<StockDailyQuote>lambdaQuery()
                            .eq(StockDailyQuote::getSymbol, symbol)
                            .eq(StockDailyQuote::getTradeDate, date)
                            .last("LIMIT 1"));
                    if (!usableQuote(quote, refreshLegacyTushare)) {
                        allQuotesComplete = false;
                        boolean preListingWarmup = Boolean.TRUE.equals(warmingUp.get(symbol))
                                && earliestKnownDate.get(symbol) != null
                                && date.isBefore(earliestKnownDate.get(symbol));
                        if (!preListingWarmup) {
                            // The frozen study counts valid observations and skips
                            // suspended dates; only the legacy path blocks future dates.
                            if (!reboundSymbols.contains(symbol)) {
                                blockedByGap.put(symbol, true);
                            }
                            progress.missingQuotes.add(new SignalBackfillRunVo.Gap(symbol, date,
                                    quote == null ? "行情未发布或缺失" : refreshLegacyTushare
                                            && "tushare".equalsIgnoreCase(quote.getDataSource())
                                            ? "旧 Tushare 行情单位与来源尚未重新同步确认"
                                            : "行情字段不完整或来源为模拟数据"));
                        }
                        progress.completedTasks++;
                        continue;
                    }
                    if (gapsBySymbol.getOrDefault(symbol, List.of()).contains(date)) {
                        progress.syncedQuotes++;
                    }
                    completeBars.merge(symbol, 1, Integer::sum);
                    if (date.equals(latestOpenDate) && !Boolean.TRUE.equals(latestRefreshSucceeded.get(symbol))) {
                        progress.completedTasks++;
                        continue;
                    }
                    boolean refreshRegular = date.equals(latestOpenDate) && date.equals(LocalDate.now(clock));
                    if (signalService.needsSignalGeneration(symbol, date, refreshRegular)) {
                        try {
                            if (Boolean.TRUE.equals(blockedByGap.get(symbol))) {
                                progress.failures.add(new SignalBackfillRunVo.Failure(symbol, date,
                                        "CALCULATING", "前序交易日行情仍有缺口，暂停后续信号计算"));
                                progress.completedTasks++;
                                continue;
                            }
                            // Shared inputs can satisfy a short-history plan even while another plan warms up.
                            // Research-specific availability is evaluated independently after factor calculation.
                            int requiredHistory = FACTOR_HISTORY_SIZE;
                            if (!hasRealFactorHistory(symbol, date, false, refreshLegacyTushare)) {
                                if (!Boolean.TRUE.equals(warmingUp.get(symbol))
                                        || completeBars.get(symbol) >= requiredHistory || date.equals(latestOpenDate)) {
                                    progress.failures.add(new SignalBackfillRunVo.Failure(symbol, date,
                                            "CALCULATING", "真实行情历史不足 " + requiredHistory + " 个交易日，未生成信号"));
                                }
                                progress.completedTasks++;
                                continue;
                            }
                            var factor = factorService.calculateAndSaveFromRealQuotes(TechnicalFactorCalculateRequestDto.builder()
                                    .symbol(symbol).tradeDate(date).build());
                            if (factor == null || factor.getFactors() == null
                                    || !"normal".equals(factor.getFactors().get("data_status"))) {
                                progress.failures.add(new SignalBackfillRunVo.Failure(symbol, date,
                                        "CALCULATING", "因子历史不足或数据不可用，未生成信号"));
                            } else {
                                progress.calculatedFactors++;
                                boolean historical = date.isBefore(LocalDate.now(clock));
                                var batch = signalService.backfillMissingSignalsBatch(
                                        symbol, date, historical ? "backfill" : "regular", refreshRegular);
                                progress.generatedSignals += batch.signals().size();
                                if (historical) progress.backfilledSignalCount += batch.signals().size();
                                for (var failure : batch.failures()) {
                                    progress.failures.add(new SignalBackfillRunVo.Failure(symbol, date, "CALCULATING",
                                            failure.strategyCode() + "/" + failure.strategyVersion() + "：" + failure.reason()));
                                }
                            }
                        } catch (RuntimeException ex) {
                            progress.failures.add(new SignalBackfillRunVo.Failure(symbol, date,
                                    "CALCULATING", clean(ex)));
                        }
                    }
                    progress.completedTasks++;
                }
                if (allQuotesComplete) {
                    progress.latestCompletedTradeDate = date;
                }
                publish(progress);
            }
            progress.status = progress.missingQuotes.isEmpty() && progress.failures.isEmpty()
                    ? "SUCCESS" : "PARTIAL";
            progress.stage = "COMPLETED";
            progress.finishedAt = LocalDateTime.now(clock);
            publish(progress);
        } catch (RuntimeException ex) {
            fail(progress, progress.stage, ex);
        } finally {
            synchronized (this) {
                if (queued.runId().equals(activeRunId)) {
                    activeRunId = null;
                }
            }
        }
    }

    private List<String> watchedSymbols(Progress progress) {
        StockWatchlist watchlist = watchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, "my-follow").last("LIMIT 1"));
        if (watchlist == null || watchlist.getId() == null) {
            return List.of();
        }
        return aShareSymbols(watchlistItemMapper.selectList(Wrappers.<StockWatchlistItem>lambdaQuery()
                        .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                        .orderByAsc(StockWatchlistItem::getSortOrder))
                .stream().map(StockWatchlistItem::getSymbol).toList(), progress);
    }

    private List<String> aShareSymbols(java.util.Collection<String> symbols, Progress progress) {
        return symbols.stream().filter(Objects::nonNull)
                .map(SymbolNormalizer::normalize).distinct()
                .filter(symbol -> {
                    if ("CN".equals(SymbolNormalizer.parseMarket(symbol))) {
                        return true;
                    }
                    progress.failures.add(new SignalBackfillRunVo.Failure(symbol, null,
                            "SCANNING", "仅支持 A 股交易日历和行情"));
                    return false;
                }).toList();
    }

    private LocalDate completedCutoff() {
        LocalDate today = LocalDate.now(clock);
        return LocalTime.now(clock).isBefore(AFTER_CLOSE) ? today.minusDays(1) : today;
    }

    private void syncCalendar(LocalDate start, LocalDate end, String runId) {
        MarketDataSyncRequestDto request = new MarketDataSyncRequestDto();
        request.setStartDate(start);
        request.setEndDate(end);
        request.setTriggerType("signal-backfill");
        request.setTriggerBy(runId);
        MarketDataSyncResultDto result = marketDataSyncService.syncTradeCalendar(request);
        if (result == null || !MarketDataSyncStatus.SUCCESS.getCode().equals(result.getStatus())) {
            throw new ServiceException("交易日历同步失败：" + syncError(result));
        }
    }

    private List<LocalDate> openDates(LocalDate start, LocalDate end) {
        List<LocalDate> result = new ArrayList<>();
        for (int pageNum = 1; ; pageNum++) {
            TradeCalendarQueryDto query = new TradeCalendarQueryDto();
            query.setMarket("CN");
            query.setOpen(true);
            query.setStartDate(start);
            query.setEndDate(end);
            query.setPageNum(pageNum);
            query.setPageSize(CALENDAR_PAGE_SIZE);
            var page = tradeCalendarService.pageTradeCalendars(query);
            List<TradeCalendar> rows = page == null || page.getRows() == null ? List.of() : page.getRows();
            for (TradeCalendar row : rows) {
                if (row.getTradeDate() != null && !"mock".equalsIgnoreCase(row.getDataSource())) {
                    result.add(row.getTradeDate());
                }
            }
            if (rows.size() < CALENDAR_PAGE_SIZE) {
                break;
            }
        }
        return result.stream().distinct().sorted().toList();
    }

    private Map<String, List<LocalDate>> scanGaps(List<String> symbols, Map<String, LocalDate> starts,
                                               List<LocalDate> dates, boolean refreshLegacyTushare) {
        Map<String, List<LocalDate>> gaps = new HashMap<>();
        for (String symbol : symbols) {
            List<LocalDate> symbolDates = dates.stream().filter(date -> !date.isBefore(starts.get(symbol))).toList();
            // Query in one range instead of one SQL statement per trading day.
            List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                    .eq(StockDailyQuote::getSymbol, symbol)
                    .ge(StockDailyQuote::getTradeDate, starts.get(symbol))
                    .le(StockDailyQuote::getTradeDate, dates.getLast()));
            Map<LocalDate, StockDailyQuote> byDate = quotes.stream().filter(q -> q.getTradeDate() != null)
                    .collect(Collectors.toMap(StockDailyQuote::getTradeDate, q -> q, (left, right) -> left));
            // Older Tushare rows retained lots/thousand yuan and ambiguous provenance.
            // Re-fetch them within this job's existing lookback instead of guessing
            // corrected historical values or overwriting another provider's rows.
            gaps.put(symbol, symbolDates.stream()
                    .filter(date -> !usableQuote(byDate.get(date), refreshLegacyTushare)).toList());
        }
        return gaps;
    }

    private List<List<LocalDate>> contiguousGaps(List<LocalDate> gaps, List<LocalDate> allDates) {
        if (gaps == null || gaps.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, Integer> positions = new HashMap<>();
        for (int i = 0; i < allDates.size(); i++) {
            positions.put(allDates.get(i), i);
        }
        List<List<LocalDate>> result = new ArrayList<>();
        List<LocalDate> current = new ArrayList<>();
        for (LocalDate date : gaps) {
            if (!current.isEmpty() && positions.get(date) != positions.get(current.getLast()) + 1) {
                result.add(current);
                current = new ArrayList<>();
            }
            current.add(date);
        }
        if (!current.isEmpty()) {
            result.add(current);
        }
        return result;
    }

    private boolean completeQuote(StockDailyQuote quote) {
        return quote != null && quote.getClosePrice() != null
                && quote.getOpenPrice() != null && quote.getHighPrice() != null
                && quote.getLowPrice() != null && quote.getVolume() != null
                && quote.getVolume().compareTo(BigDecimal.ZERO) > 0
                && quote.getDataSource() != null && !"mock".equalsIgnoreCase(quote.getDataSource());
    }

    private boolean usableQuote(StockDailyQuote quote, boolean refreshLegacyTushare) {
        return completeQuote(quote) && (!refreshLegacyTushare
                || !"tushare".equalsIgnoreCase(quote.getDataSource()));
    }

    private boolean hasRealFactorHistory(String symbol, LocalDate date, boolean rebound,
                                         boolean refreshLegacyTushare) {
        // The factor calculation uses up to 80 real rows; ensure at least its
        // 26-bar minimum exists before spending a calculation attempt.
        List<StockDailyQuote> history = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, symbol)
                .le(StockDailyQuote::getTradeDate, date)
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last(rebound ? "LIMIT 450" : "LIMIT 80"));
        if (history == null) {
            return false;
        }
        long realComplete = history.stream().filter(quote -> rebound
                ? ResearchReboundApplicationScope.validQuote(quote, true)
                : usableQuote(quote, refreshLegacyTushare)).count();
        if (refreshLegacyTushare && !rebound) {
            // The factor service re-reads all non-mock rows; having 26 good
            // older rows does not make a mixed-unit row in its recent window safe.
            List<StockDailyQuote> recentInput = history.stream().filter(Objects::nonNull)
                    .filter(quote -> quote.getTradeDate() != null && !quote.getTradeDate().isAfter(date))
                    .filter(quote -> quote.getDataSource() != null
                            && !"mock".equalsIgnoreCase(quote.getDataSource()))
                    .filter(quote -> quote.getClosePrice() != null && quote.getVolume() != null)
                    .sorted(Comparator.comparing(StockDailyQuote::getTradeDate).reversed())
                    .limit(FACTOR_HISTORY_SIZE).toList();
            if (recentInput.size() < FACTOR_HISTORY_SIZE
                    || recentInput.stream().anyMatch(quote -> !usableQuote(quote, true))) {
                return false;
            }
        }
        return realComplete >= (rebound ? 252 : FACTOR_HISTORY_SIZE);
    }

    private boolean syncReboundBenchmark(LocalDate firstSignalDate, LocalDate lastSignalDate,
                                         String runId, Progress progress) {
        DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
        request.setTargetSymbol("000300.SH");
        request.setStartDate(firstSignalDate.minusDays(120));
        request.setEndDate(lastSignalDate);
        request.setTriggerType("signal-backfill");
        request.setTriggerBy(runId);
        try {
            MarketDataSyncResultDto result = marketDataSyncService.syncDailyQuotes(request);
            if (result != null && MarketDataSyncStatus.SUCCESS.getCode().equals(result.getStatus())
                    && (result.getFailed() == null || result.getFailed() == 0)
                    && (result.getScanned() == null || result.getScanned() > 0)
                    && hasReboundBenchmark(lastSignalDate)) {
                return true;
            }
            progress.failures.add(new SignalBackfillRunVo.Failure("000300.SH", lastSignalDate,
                    "SYNCING_QUOTES", "沪深300行情同步失败或缺少同日数据：" + syncError(result)));
        } catch (RuntimeException ex) {
            progress.failures.add(new SignalBackfillRunVo.Failure("000300.SH", lastSignalDate,
                    "SYNCING_QUOTES", clean(ex)));
        }
        return false;
    }

    private boolean hasReboundBenchmark(LocalDate date) {
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, "000300.SH")
                .le(StockDailyQuote::getTradeDate, date)
                .orderByDesc(StockDailyQuote::getTradeDate).last("LIMIT 120"));
        if (quotes == null) {
            return false;
        }
        List<StockDailyQuote> valid = quotes.stream()
                .filter(quote -> ResearchReboundApplicationScope.validQuote(quote, false)).toList();
        return valid.size() >= 60 && valid.stream().anyMatch(quote -> date.equals(quote.getTradeDate()));
    }

    private String syncError(MarketDataSyncResultDto result) {
        if (result == null) {
            return "数据源无响应";
        }
        return result.getErrors() == null || result.getErrors().isEmpty()
                ? "数据源返回 " + result.getStatus() : String.join("；", result.getErrors());
    }

    private void fail(Progress progress, String stage, RuntimeException ex) {
        progress.failures.add(new SignalBackfillRunVo.Failure("", null, stage, clean(ex)));
        progress.status = "FAILED";
        progress.stage = "COMPLETED";
        progress.finishedAt = LocalDateTime.now(clock);
        publish(progress);
    }

    private String clean(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName()
                : message.substring(0, Math.min(240, message.length()));
    }

    private void publish(Progress progress) {
        save(progress.snapshot(), false);
    }

    private void save(SignalBackfillRunVo snapshot, boolean insert) {
        SignalBackfillRun row = new SignalBackfillRun();
        row.setRunId(snapshot.runId());
        row.setStatus(snapshot.status());
        row.setSnapshotJson(json(snapshot));
        row.setStartedAt(snapshot.startedAt());
        row.setFinishedAt(snapshot.finishedAt());
        if (insert) {
            runMapper.insert(row);
        } else {
            runMapper.updateById(row);
        }
    }

    private String json(SignalBackfillRunVo snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("补齐任务状态序列化失败", ex);
        }
    }

    private SignalBackfillRunVo parse(SignalBackfillRun row) {
        try {
            return objectMapper.readValue(row.getSnapshotJson(), SignalBackfillRunVo.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("补齐任务状态读取失败", ex);
        }
    }

    private static final class Progress {
        private final String runId;
        private final LocalDateTime startedAt;
        private String status;
        private String stage;
        private LocalDateTime finishedAt;
        private LocalDate latestCompletedTradeDate;
        private int totalSymbols;
        private int totalDates;
        private int totalTasks;
        private int completedTasks;
        private int syncedQuotes;
        private int calculatedFactors;
        private int generatedSignals;
        private int backfilledSignalCount;
        private final List<SignalBackfillRunVo.Gap> missingQuotes = new ArrayList<>();
        private final List<SignalBackfillRunVo.Failure> failures = new ArrayList<>();

        private Progress(SignalBackfillRunVo initial) {
            this.runId = initial.runId();
            this.startedAt = initial.startedAt();
            this.status = initial.status();
            this.stage = initial.stage();
        }

        private SignalBackfillRunVo snapshot() {
            return new SignalBackfillRunVo(runId, status, stage, startedAt, finishedAt,
                    latestCompletedTradeDate, totalSymbols, totalDates, totalTasks, completedTasks,
                    syncedQuotes, calculatedFactors, generatedSignals, backfilledSignalCount,
                    List.copyOf(missingQuotes), List.copyOf(failures));
        }
    }
}
