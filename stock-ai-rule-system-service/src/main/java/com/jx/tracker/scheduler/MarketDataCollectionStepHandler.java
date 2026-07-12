package com.jx.tracker.scheduler;

import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.TradeCalendar;
import com.jx.tracker.domain.enums.MarketDataSyncStatus;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.TradeCalendarQueryDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MarketDataCollectionStepHandler implements DailyWorkflowStepHandler {

    private final MarketDataSyncService marketDataSyncService;
    private final TradeCalendarService tradeCalendarService;

    public MarketDataCollectionStepHandler(
            MarketDataSyncService marketDataSyncService,
            TradeCalendarService tradeCalendarService) {
        this.marketDataSyncService = marketDataSyncService;
        this.tradeCalendarService = tradeCalendarService;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.MARKET_DATA_COLLECTION;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        var request = context.getRequest();
        MarketDataSyncRequestDto syncRequest = syncRequest(context);
        MarketDataSyncResultDto stockList = marketDataSyncService.syncStockList(syncRequest);
        requireSuccess(stockList, "股票列表");
        MarketDataSyncResultDto tradeCalendar = marketDataSyncService.syncTradeCalendar(syncRequest);
        requireSuccess(tradeCalendar, "交易日历");
        java.time.LocalDate tradeDate = latestOpenTradeDate(request.getTradeDate());
        request.setTradeDate(tradeDate);
        List<MarketDataSyncResultDto> dailyQuotes = new ArrayList<>();
        if (request.getSymbols() == null || request.getSymbols().isEmpty()) {
            dailyQuotes.add(syncDailyQuotes(quoteRequest(context, null, tradeDate), "全市场收盘快照"));
            dailyQuotes.add(syncDailyQuotes(
                    quoteRequest(context, "000300.SH", tradeDate.minusDays(120)), "沪深 300 日线"));
        } else {
            for (String symbol : request.getSymbols()) {
                dailyQuotes.add(syncDailyQuotes(quoteRequest(context, symbol, tradeDate), symbol + " 日线"));
            }
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
