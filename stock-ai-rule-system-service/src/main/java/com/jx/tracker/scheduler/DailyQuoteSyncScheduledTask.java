package com.jx.tracker.scheduler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.domain.entity.StockWatchlist;
import com.jx.tracker.domain.entity.StockWatchlistItem;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.mapper.StockWatchlistItemMapper;
import com.jx.tracker.mapper.StockWatchlistMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Keeps the watchlist's recent daily bars fresh without requiring the optional
 * AI/rule workflow to be enabled. The research endpoint remains a best-effort
 * last-mile recovery path when this task or an external provider is unavailable.
 */
@Component
public class DailyQuoteSyncScheduledTask {

    private final MarketDataSyncService marketDataSyncService;
    private final TradeCalendarService tradeCalendarService;
    private final StockWatchlistMapper stockWatchlistMapper;
    private final StockWatchlistItemMapper stockWatchlistItemMapper;

    @Value("${stock-ai-rule.scheduler.quote-sync-enabled:true}")
    private boolean quoteSyncEnabled;

    public DailyQuoteSyncScheduledTask(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            StockWatchlistMapper stockWatchlistMapper,
            StockWatchlistItemMapper stockWatchlistItemMapper) {
        this.marketDataSyncService = marketDataSyncService;
        this.tradeCalendarService = tradeCalendarService;
        this.stockWatchlistMapper = stockWatchlistMapper;
        this.stockWatchlistItemMapper = stockWatchlistItemMapper;
    }

    @Scheduled(cron = "${stock-ai-rule.scheduler.quote-sync-cron:0 15 18 * * MON-FRI}",
            zone = "${stock-ai-rule.scheduler.zone:Asia/Shanghai}")
    public void syncWatchlistQuotes() {
        if (!quoteSyncEnabled) {
            return;
        }
        LocalDate tradeDate = latestOpenTradeDate();
        if (tradeDate == null) {
            return;
        }
        List<String> symbols = watchedSymbols();
        for (String symbol : symbols) {
            DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
            request.setTargetSymbol(symbol);
            // 45 个自然日接近最低交易日数量，节假日较多时仍可能不足 26 日；
            // 留出余量，确保后续技术因子可以稳定计算。
            request.setStartDate(tradeDate.minusDays(60));
            request.setEndDate(tradeDate);
            request.setTriggerType("scheduled-quote-sync");
            request.setTriggerBy("watchlist:" + symbol);
            try {
                marketDataSyncService.syncDailyQuotes(request);
            } catch (RuntimeException ignored) {
                // One unavailable symbol must not prevent the remaining watchlist
                // from being refreshed on the next scheduled run.
            }
        }
    }

    private LocalDate latestOpenTradeDate() {
        TradeCalendarQueryDto query = new TradeCalendarQueryDto();
        query.setMarket("CN");
        query.setOpen(true);
        query.setEndDate(LocalDate.now());
        query.setPageNum(1);
        query.setPageSize(1);
        var page = tradeCalendarService.pageTradeCalendars(query);
        if (page != null && page.getRows() != null && !page.getRows().isEmpty()) {
            return page.getRows().getFirst().getTradeDate();
        }
        MarketDataSyncRequestDto calendarRequest = new MarketDataSyncRequestDto();
        calendarRequest.setStartDate(LocalDate.now().minusDays(14));
        calendarRequest.setEndDate(LocalDate.now());
        calendarRequest.setTriggerType("scheduled-quote-sync");
        calendarRequest.setTriggerBy("trade-calendar");
        try {
            marketDataSyncService.syncTradeCalendar(calendarRequest);
        } catch (RuntimeException ignored) {
            return null;
        }
        page = tradeCalendarService.pageTradeCalendars(query);
        return page == null || page.getRows() == null || page.getRows().isEmpty()
                ? null : page.getRows().getFirst().getTradeDate();
    }

    private List<String> watchedSymbols() {
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
}
