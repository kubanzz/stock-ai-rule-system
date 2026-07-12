package com.jx.tracker.scheduler;

import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MarketDataCollectionStepHandler implements DailyWorkflowStepHandler {

    private final MarketDataSyncService marketDataSyncService;

    public MarketDataCollectionStepHandler(MarketDataSyncService marketDataSyncService) {
        this.marketDataSyncService = marketDataSyncService;
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
        MarketDataSyncResultDto tradeCalendar = marketDataSyncService.syncTradeCalendar(syncRequest);
        List<MarketDataSyncResultDto> dailyQuotes = new ArrayList<>();
        if (request.getSymbols() == null || request.getSymbols().isEmpty()) {
            dailyQuotes.add(marketDataSyncService.syncDailyQuotes(quoteRequest(context, null, request.getTradeDate())));
            dailyQuotes.add(marketDataSyncService.syncDailyQuotes(quoteRequest(
                    context, "000300.SH", request.getTradeDate().minusDays(120))));
        } else {
            for (String symbol : request.getSymbols()) {
                dailyQuotes.add(marketDataSyncService.syncDailyQuotes(
                        quoteRequest(context, symbol, request.getTradeDate())));
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
}
