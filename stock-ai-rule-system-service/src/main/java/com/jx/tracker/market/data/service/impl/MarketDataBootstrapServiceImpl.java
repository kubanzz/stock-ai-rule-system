package com.jx.tracker.market.data.service.impl;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.enums.MarketDataSyncStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.service.MarketDataBootstrapService;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.market.data.util.MarketCodeNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class MarketDataBootstrapServiceImpl implements MarketDataBootstrapService {

    private static final String A_SHARE_MARKET = "A股";
    private static final String HS300_SYMBOL = "000300.SH";

    private final MarketDataSyncService marketDataSyncService;
    private final TradeCalendarService tradeCalendarService;
    private final TaskExecutor taskExecutor;
    private final Clock clock = Clock.systemDefaultZone();

    public MarketDataBootstrapServiceImpl(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService,
            @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.marketDataSyncService = marketDataSyncService;
        this.tradeCalendarService = tradeCalendarService;
        this.taskExecutor = taskExecutor;
    }

    @Override
    public BootstrapAccepted start(String market, String triggerBy) {
        String normalizedMarket = MarketCodeNormalizer.toDisplayName(market);
        if (!MarketCodeNormalizer.equivalent(A_SHARE_MARKET, normalizedMarket)) {
            throw new ServiceException("一期初始化仅支持 A 股市场");
        }
        String jobId = UUID.randomUUID().toString();
        String operator = StringUtils.hasText(triggerBy) ? triggerBy.trim() : "manual";
        taskExecutor.execute(() -> runBootstrap(jobId, operator));
        return new BootstrapAccepted(jobId, A_SHARE_MARKET, "accepted");
    }

    private void runBootstrap(String jobId, String triggerBy) {
        try {
            LocalDate today = LocalDate.now(clock);
            MarketDataSyncRequestDto stocks = request(today.minusDays(1), today, triggerBy);
            requireSuccess(marketDataSyncService.syncStockList(stocks), "股票列表");

            MarketDataSyncRequestDto calendar = request(today.withDayOfYear(1), today.plusYears(1), triggerBy);
            requireSuccess(marketDataSyncService.syncTradeCalendar(calendar), "交易日历");

            LocalDate tradeDate = latestOpenTradeDate(today);
            DailyQuoteSyncRequestDto snapshot = dailyRequest(null, tradeDate, tradeDate, triggerBy);
            requireSuccess(marketDataSyncService.syncDailyQuotes(snapshot), "全市场收盘快照");

            DailyQuoteSyncRequestDto benchmark = dailyRequest(
                    HS300_SYMBOL, tradeDate.minusDays(120), tradeDate, triggerBy);
            requireSuccess(marketDataSyncService.syncDailyQuotes(benchmark), "沪深 300 日线");
        } catch (RuntimeException exception) {
            log.error("A 股初始化任务失败，jobId={}", jobId, exception);
        }
    }

    private LocalDate latestOpenTradeDate(LocalDate today) {
        TradeCalendarQueryDto query = new TradeCalendarQueryDto();
        query.setMarket(A_SHARE_MARKET);
        query.setOpen(true);
        query.setEndDate(today);
        query.setPageNum(1);
        query.setPageSize(1);
        PageResult<TradeCalendar> page = tradeCalendarService.pageTradeCalendars(query);
        List<TradeCalendar> rows = page == null || page.getRows() == null ? List.of() : page.getRows();
        if (rows.isEmpty() || rows.getFirst().getTradeDate() == null) {
            throw new ServiceException("未找到最近 A 股交易日");
        }
        return rows.getFirst().getTradeDate();
    }

    private MarketDataSyncRequestDto request(LocalDate startDate, LocalDate endDate, String triggerBy) {
        MarketDataSyncRequestDto request = new MarketDataSyncRequestDto();
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setTriggerType("bootstrap");
        request.setTriggerBy(triggerBy);
        return request;
    }

    private DailyQuoteSyncRequestDto dailyRequest(
            String symbol,
            LocalDate startDate,
            LocalDate endDate,
            String triggerBy) {
        DailyQuoteSyncRequestDto request = new DailyQuoteSyncRequestDto();
        request.setTargetSymbol(symbol);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setTriggerType("bootstrap");
        request.setTriggerBy(triggerBy);
        return request;
    }

    private void requireSuccess(MarketDataSyncResultDto result, String operation) {
        if (result == null || !MarketDataSyncStatus.SUCCESS.getCode().equals(result.getStatus())) {
            throw new ServiceException(operation + "同步失败");
        }
    }
}
