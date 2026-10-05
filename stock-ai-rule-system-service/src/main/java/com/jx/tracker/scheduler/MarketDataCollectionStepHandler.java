package com.jx.tracker.scheduler;

import com.jx.tracker.common.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.enums.MarketDataSyncStatus;
import com.jx.tracker.domain.vo.DailyWorkflowStepResultVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.service.ResearchReboundApplicationScope;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class MarketDataCollectionStepHandler implements DailyWorkflowStepHandler {

    private final MarketDataSyncService marketDataSyncService;
    private final TradeCalendarService tradeCalendarService;
    private final StockWatchlistMapper stockWatchlistMapper;
    private final StockWatchlistItemMapper stockWatchlistItemMapper;
    private final StockDailyQuoteMapper stockDailyQuoteMapper;
    private final ResearchReboundApplicationScope reboundScope;

    public MarketDataCollectionStepHandler(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService) {
        this(marketDataSyncService, tradeCalendarService, null, null, null);
    }

    public MarketDataCollectionStepHandler(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper stockWatchlistMapper,
            StockWatchlistItemMapper stockWatchlistItemMapper) {
        this(marketDataSyncService, tradeCalendarService, stockWatchlistMapper, stockWatchlistItemMapper, null);
    }

    public MarketDataCollectionStepHandler(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper stockWatchlistMapper,
            StockWatchlistItemMapper stockWatchlistItemMapper,
            StockDailyQuoteMapper stockDailyQuoteMapper) {
        this(marketDataSyncService, tradeCalendarService, stockWatchlistMapper,
                stockWatchlistItemMapper, stockDailyQuoteMapper, null);
    }

    @Autowired
    public MarketDataCollectionStepHandler(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper stockWatchlistMapper,
            StockWatchlistItemMapper stockWatchlistItemMapper,
            StockDailyQuoteMapper stockDailyQuoteMapper,
            ResearchReboundApplicationScope reboundScope) {
        this.reboundScope = reboundScope;
        this.marketDataSyncService = marketDataSyncService;
        this.tradeCalendarService = tradeCalendarService;
        this.stockWatchlistMapper = stockWatchlistMapper;
        this.stockWatchlistItemMapper = stockWatchlistItemMapper;
        this.stockDailyQuoteMapper = stockDailyQuoteMapper;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.MARKET_DATA_COLLECTION;
    }

    @Override
    public DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        // Resolve the bound application first, including explicit requests, so
        // neither a provider fallback nor an empty intersection broadens its scope.
        resolveWatchedSymbols(context);
        try {
            return executeSync(context, startedAt);
        } catch (ServiceException failure) {
            if (canUseLocalQuotes(context)) {
                return localQuoteFallback(context, startedAt, "行情同步不可用，已使用本地行情继续因子计算。", failure.getMessage());
            }
            throw failure;
        }
    }

    private void resolveWatchedSymbols(DailyWorkflowContext context) {
        if (context == null || context.getRequest() == null) {
            return;
        }
        if (reboundScope != null && reboundScope.hasBoundStockPool()) {
            List<String> symbols = reboundScope.restrictToApplication(context.getRequest().getSymbols());
            if (symbols.isEmpty()) {
                throw new ServiceException("当前请求与应用方案绑定的股票分组没有可用交集，已停止任务");
            }
            context.getRequest().setSymbols(symbols);
            return;
        }
        if (context.getRequest().getSymbols() == null || !context.getRequest().getSymbols().isEmpty()) {
            return;
        }
        Set<String> symbols = new java.util.LinkedHashSet<>(reboundScope == null
                ? Set.of() : reboundScope.activeApplicationSymbols());
        symbols.addAll(watchedSymbols());
        if (!symbols.isEmpty()) context.getRequest().setSymbols(List.copyOf(symbols));
    }

    private DailyWorkflowStepResultVo localQuoteFallback(
            DailyWorkflowContext context, LocalDateTime startedAt, String message) {
        return localQuoteFallback(context, startedAt, message, null);
    }

    private DailyWorkflowStepResultVo localQuoteFallback(
            DailyWorkflowContext context, LocalDateTime startedAt, String message, String failureMessage) {
        if (context != null) {
            context.putAttribute("localQuoteFallback", Boolean.TRUE);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("recoverable", true);
        details.put("localQuoteFallback", true);
        details.put("remoteSyncSkipped", failureMessage == null);
        if (failureMessage != null) {
            details.put("message", failureMessage);
        }
        return DailyWorkflowStepResults.success(stepCode(), startedAt, message, details);
    }

    private DailyWorkflowStepResultVo executeSync(DailyWorkflowContext context, LocalDateTime startedAt) {
        var request = context.getRequest();
        MarketDataSyncRequestDto syncRequest = syncRequest(context);
        MarketDataSyncResultDto stockList = marketDataSyncService.syncStockList(syncRequest);
        requireSuccess(stockList, "股票列表");
        MarketDataSyncResultDto tradeCalendar = marketDataSyncService.syncTradeCalendar(syncRequest);
        requireSuccess(tradeCalendar, "交易日历");
        java.time.LocalDate tradeDate = latestOpenTradeDate(request.getTradeDate());
        request.setTradeDate(tradeDate);
        List<MarketDataSyncResultDto> dailyQuotes = new ArrayList<>();
        List<String> requestedSymbols = request.getSymbols();
        Set<String> reboundSymbols = reboundScope == null ? Set.of() : reboundScope.activeSymbols();
        List<String> watchlistSymbols = requestedSymbols == null || requestedSymbols.isEmpty()
                ? watchedSymbols() : List.of();
        if (!watchlistSymbols.isEmpty()) {
            // 将自动发现的关注股票传给后续因子计算和信号生成步骤，避免只同步行情而不生成信号。
            request.setSymbols(watchlistSymbols);
            for (String symbol : watchlistSymbols) {
                // 30 个自然日通常只有约 20 个交易日，达不到技术因子
                // 计算要求的 26 个有效交易日；保留更充足的历史余量。
                dailyQuotes.add(syncDailyQuotes(
                        quoteRequest(context, symbol, quoteHistoryStart(symbol, tradeDate, reboundSymbols)),
                        symbol + " 历史行情"));
            }
        } else if (requestedSymbols == null || requestedSymbols.isEmpty()) {
            dailyQuotes.add(syncDailyQuotes(quoteRequest(context, null, tradeDate), "全市场收盘快照"));
            dailyQuotes.add(syncDailyQuotes(
                    quoteRequest(context, "000300.SH", tradeDate.minusDays(120)), "沪深 300 日线"));
        } else {
            for (String symbol : requestedSymbols) {
                dailyQuotes.add(syncDailyQuotes(
                        quoteRequest(context, symbol, quoteHistoryStart(symbol, tradeDate, reboundSymbols)),
                        symbol + " 历史行情"));
            }
        }
        if (request.getSymbols() != null
                && request.getSymbols().stream().anyMatch(reboundSymbols::contains)) {
            dailyQuotes.add(syncDailyQuotes(quoteRequest(context, "000300.SH", tradeDate.minusDays(120)),
                    "沪深300日线"));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stockListStatus", stockList.getStatus());
        details.put("tradeCalendarStatus", tradeCalendar.getStatus());
        details.put("dailyQuoteSyncCount", dailyQuotes.size());
        details.put("inserted", safeInt(stockList.getInserted()) + safeInt(tradeCalendar.getInserted())
                + dailyQuotes.stream().mapToInt(result -> safeInt(result.getInserted())).sum());
        details.put("updated", safeInt(stockList.getUpdated()) + safeInt(tradeCalendar.getUpdated())
                + dailyQuotes.stream().mapToInt(result -> safeInt(result.getUpdated())).sum());
        details.put("failed", safeInt(stockList.getFailed()) + safeInt(tradeCalendar.getFailed())
                + dailyQuotes.stream().mapToInt(result -> safeInt(result.getFailed())).sum());
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "行情数据同步完成。",
                details
        );
    }

    private LocalDate quoteHistoryStart(String symbol, LocalDate tradeDate, Set<String> reboundSymbols) {
        return reboundSymbols.contains(symbol) ? reboundScope.historyStart(symbol, tradeDate)
                : tradeDate.minusDays(60);
    }

    private boolean canUseLocalQuotes(DailyWorkflowContext context) {
        if (stockDailyQuoteMapper == null || context == null || context.getRequest() == null
                || context.getRequest().getSymbols() == null || context.getRequest().getSymbols().isEmpty()
                || context.getRequest().getTradeDate() == null) return false;
        Set<LocalDate> commonDates = null;
        for (String symbol : context.getRequest().getSymbols().stream().distinct().toList()) {
            Set<LocalDate> dates = new HashSet<>(stockDailyQuoteMapper.selectList(
                    new QueryWrapper<StockDailyQuote>()
                            .eq("symbol", symbol)
                            .le("trade_date", context.getRequest().getTradeDate())
                            .isNotNull("close_price")
                            .select("trade_date"))
                    .stream().map(StockDailyQuote::getTradeDate)
                    .filter(Objects::nonNull).toList());
            if (dates.isEmpty()) return false;
            if (commonDates == null) commonDates = dates;
            else commonDates.retainAll(dates);
            if (commonDates.isEmpty()) return false;
        }
        LocalDate fallbackDate = commonDates.stream().max(LocalDate::compareTo).orElse(null);
        if (fallbackDate == null) return false;
        context.getRequest().setTradeDate(fallbackDate);
        return true;
    }

    private MarketDataSyncRequestDto syncRequest(DailyWorkflowContext context) {
        MarketDataSyncRequestDto request = new MarketDataSyncRequestDto();
        request.setStartDate(context.getRequest().getTradeDate());
        request.setEndDate(context.getRequest().getTradeDate());
        request.setTriggerType(context.getTriggerType().getCode());
        request.setTriggerBy("daily-workflow:" + context.getRunId());
        return request;
    }

    private DailyQuoteSyncRequestDto quoteRequest(
            DailyWorkflowContext context,
            String symbol,
            java.time.LocalDate startDate) {
        DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
        request.setTargetSymbol(symbol);
        request.setStartDate(startDate);
        request.setEndDate(context.getRequest().getTradeDate());
        request.setTriggerType(context.getTriggerType().getCode());
        request.setTriggerBy("daily-workflow:" + context.getRunId());
        return request;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private List<String> watchedSymbols() {
        if (stockWatchlistMapper == null || stockWatchlistItemMapper == null) {
            return List.of();
        }
        StockWatchlist watchlist = stockWatchlistMapper.selectOne(Wrappers.<StockWatchlist>lambdaQuery()
                .eq(StockWatchlist::getPoolCode, "my-follow")
                .last("LIMIT 1"));
        if (watchlist == null || watchlist.getId() == null) {
            return List.of();
        }
        return stockWatchlistItemMapper.selectList(Wrappers.<StockWatchlistItem>lambdaQuery()
                        .eq(StockWatchlistItem::getWatchlistId, watchlist.getId())
                        .orderByAsc(StockWatchlistItem::getSortOrder)
                        .orderByAsc(StockWatchlistItem::getId))
                .stream()
                .map(StockWatchlistItem::getSymbol)
                .filter(symbol -> symbol != null && !symbol.isBlank())
                .distinct()
                .toList();
    }

    private java.time.LocalDate latestOpenTradeDate(java.time.LocalDate requestedDate) {
        TradeCalendarQueryDto query = new TradeCalendarQueryDto();
        query.setMarket("CN");
        query.setOpen(true);
        query.setEndDate(requestedDate);
        query.setPageNum(1);
        query.setPageSize(1);
        PageResult<TradeCalendar> page = tradeCalendarService.pageTradeCalendars(query);
        List<TradeCalendar> rows = page == null || page.getRows() == null ? List.of() : page.getRows();
        if (rows.isEmpty() || rows.getFirst().getTradeDate() == null) {
            throw new ServiceException("未找到最近 A 股交易日，取消行情写入");
        }
        return rows.getFirst().getTradeDate();
    }

    private MarketDataSyncResultDto syncDailyQuotes(DailyQuoteSyncRequestDto request, String operation) {
        MarketDataSyncResultDto result = marketDataSyncService.syncDailyQuotes(request);
        requireSuccess(result, operation);
        return result;
    }

    private void requireSuccess(MarketDataSyncResultDto result, String operation) {
        if (result == null || !MarketDataSyncStatus.SUCCESS.getCode().equals(result.getStatus())) {
            throw new ServiceException(operation + "同步失败，停止后续因子与信号任务");
        }
    }
}
