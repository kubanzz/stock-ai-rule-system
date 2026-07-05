package com.jx.tracker.market.data.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.entity.MarketDataSyncRun;
import com.jx.tracker.domain.enums.MarketDataSyncStatus;
import com.jx.tracker.domain.enums.MarketDataSyncType;
import com.jx.tracker.mapper.MarketDataSyncRunMapper;
import com.jx.tracker.market.data.dto.DailyQuoteSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataImportResultDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRequestDto;
import com.jx.tracker.market.data.dto.MarketDataSyncResultDto;
import com.jx.tracker.market.data.dto.MarketDataSyncRunQueryDto;
import com.jx.tracker.market.data.dto.StockBaseUpsertDto;
import com.jx.tracker.market.data.dto.StockDailyQuoteUpsertDto;
import com.jx.tracker.market.data.dto.TradeCalendarDto;
import com.jx.tracker.market.data.provider.MarketDataProvider;
import com.jx.tracker.market.data.provider.MarketDataProviderResolver;
import com.jx.tracker.market.data.provider.MarketDataProviderSelection;
import com.jx.tracker.market.data.service.MarketDataSyncService;
import com.jx.tracker.market.data.service.StockBaseService;
import com.jx.tracker.market.data.service.StockDailyQuoteService;
import com.jx.tracker.market.data.service.TradeCalendarService;
import com.jx.tracker.market.data.util.MarketDataNormalizer;
import com.jx.tracker.market.data.util.SymbolNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class MarketDataSyncServiceImpl implements MarketDataSyncService {

    private final MarketDataProviderResolver providerResolver;

    private final StockBaseService stockBaseService;

    private final StockDailyQuoteService stockDailyQuoteService;

    private final TradeCalendarService tradeCalendarService;

    private final MarketDataSyncRunMapper marketDataSyncRunMapper;

    private final ObjectMapper objectMapper;

    public MarketDataSyncServiceImpl(
            MarketDataProviderResolver providerResolver,
            StockBaseService stockBaseService,
            StockDailyQuoteService stockDailyQuoteService,
            TradeCalendarService tradeCalendarService,
            MarketDataSyncRunMapper marketDataSyncRunMapper,
            ObjectMapper objectMapper) {
        this.providerResolver = providerResolver;
        this.stockBaseService = stockBaseService;
        this.stockDailyQuoteService = stockDailyQuoteService;
        this.tradeCalendarService = tradeCalendarService;
        this.marketDataSyncRunMapper = marketDataSyncRunMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketDataSyncResultDto syncStockList(MarketDataSyncRequestDto request) {
        MarketDataSyncRequestDto safeRequest = request == null ? new MarketDataSyncRequestDto() : request;
        MarketDataProviderSelection selection = providerResolver.resolve();
        MarketDataSyncRun run = startRun(
                selection,
                MarketDataSyncType.STOCK_LIST.getCode(),
                requestParams(safeRequest, selection),
                null,
                safeRequest.getStartDate(),
                safeRequest.getEndDate(),
                safeRequest.getTriggerType(),
                safeRequest.getTriggerBy()
        );
        try {
            MarketDataProvider provider = selection.provider();
            List<StockBaseUpsertDto> rows = provider.fetchStockList();
            MarketDataImportResultDto<StockBaseUpsertDto> importResult = stockBaseService.upsertStockBases(rows);
            return finishSuccess(run, selection, importResult, null, safeRequest.getStartDate(), safeRequest.getEndDate());
        } catch (RuntimeException ex) {
            return finishFailed(run, selection, ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketDataSyncResultDto syncDailyQuotes(DailyQuoteSyncRequestDto request) {
        DailyQuoteSyncRequestDto safeRequest = request == null ? new DailyQuoteSyncRequestDto() : request;
        String targetSymbol = SymbolNormalizer.normalize(safeRequest.getTargetSymbol());
        MarketDataProviderSelection selection = providerResolver.resolve();
        MarketDataSyncRun run = startRun(
                selection,
                MarketDataSyncType.DAILY_QUOTE.getCode(),
                requestParams(safeRequest, selection),
                targetSymbol,
                safeRequest.getStartDate(),
                safeRequest.getEndDate(),
                safeRequest.getTriggerType(),
                safeRequest.getTriggerBy()
        );
        try {
            List<StockDailyQuoteUpsertDto> rows = selection.provider()
                    .fetchDailyQuotes(targetSymbol, safeRequest.getStartDate(), safeRequest.getEndDate());
            MarketDataImportResultDto<StockDailyQuoteUpsertDto> importResult = stockDailyQuoteService.upsertDailyQuotes(rows);
            return finishSuccess(run, selection, importResult, targetSymbol, safeRequest.getStartDate(), safeRequest.getEndDate());
        } catch (RuntimeException ex) {
            return finishFailed(run, selection, ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MarketDataSyncResultDto syncTradeCalendar(MarketDataSyncRequestDto request) {
        MarketDataSyncRequestDto safeRequest = request == null ? new MarketDataSyncRequestDto() : request;
        MarketDataProviderSelection selection = providerResolver.resolve();
        MarketDataSyncRun run = startRun(
                selection,
                MarketDataSyncType.TRADE_CALENDAR.getCode(),
                requestParams(safeRequest, selection),
                null,
                safeRequest.getStartDate(),
                safeRequest.getEndDate(),
                safeRequest.getTriggerType(),
                safeRequest.getTriggerBy()
        );
        try {
            List<TradeCalendarDto> rows = selection.provider()
                    .fetchTradeCalendar(safeRequest.getStartDate(), safeRequest.getEndDate());
            MarketDataImportResultDto<TradeCalendarDto> importResult = tradeCalendarService.upsertTradeCalendars(rows);
            return finishSuccess(run, selection, importResult, null, safeRequest.getStartDate(), safeRequest.getEndDate());
        } catch (RuntimeException ex) {
            return finishFailed(run, selection, ex);
        }
    }

    @Override
    public PageResult<MarketDataSyncRun> pageSyncRuns(MarketDataSyncRunQueryDto query) {
        MarketDataSyncRunQueryDto safeQuery = query == null ? new MarketDataSyncRunQueryDto() : query;
        String targetSymbol = SymbolNormalizer.normalize(safeQuery.getTargetSymbol());
        LambdaQueryWrapper<MarketDataSyncRun> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(safeQuery.getDataSource()), MarketDataSyncRun::getDataSource, safeQuery.getDataSource())
                .eq(StringUtils.hasText(safeQuery.getSyncType()), MarketDataSyncRun::getSyncType, safeQuery.getSyncType())
                .eq(StringUtils.hasText(safeQuery.getStatus()), MarketDataSyncRun::getStatus, safeQuery.getStatus())
                .eq(targetSymbol != null, MarketDataSyncRun::getTargetSymbol, targetSymbol)
                .ge(safeQuery.getStartDate() != null, MarketDataSyncRun::getStartedAt, safeQuery.getStartDate().atStartOfDay())
                .le(safeQuery.getEndDate() != null, MarketDataSyncRun::getStartedAt, safeQuery.getEndDate().plusDays(1).atStartOfDay())
                .orderByDesc(MarketDataSyncRun::getStartedAt);
        Page<MarketDataSyncRun> page = marketDataSyncRunMapper.selectPage(new Page<>(pageNum(safeQuery.getPageNum()), pageSize(safeQuery.getPageSize())), wrapper);
        return PageResult.getDataTable(page.getRecords(), page.getTotal());
    }

    private MarketDataSyncRun startRun(
            MarketDataProviderSelection selection,
            String syncType,
            String requestParams,
            String targetSymbol,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate,
            String triggerType,
            String triggerBy) {
        MarketDataSyncRun run = MarketDataSyncRun.builder()
                .dataSource(selection.dataSource())
                .syncType(syncType)
                .status(MarketDataSyncStatus.RUNNING.getCode())
                .requestParams(requestParams)
                .targetSymbol(targetSymbol)
                .startDate(startDate)
                .endDate(endDate)
                .triggerType(StringUtils.hasText(triggerType) ? triggerType : "manual")
                .triggerBy(triggerBy)
                .scanned(0)
                .inserted(0)
                .updated(0)
                .skipped(0)
                .failed(0)
                .startedAt(LocalDateTime.now())
                .build();
        marketDataSyncRunMapper.insert(run);
        return run;
    }

    private MarketDataSyncResultDto finishSuccess(
            MarketDataSyncRun run,
            MarketDataProviderSelection selection,
            MarketDataImportResultDto<?> importResult,
            String targetSymbol,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate) {
        int failed = importResult == null ? 0 : importResult.getRejectedCount();
        run.setStatus(MarketDataSyncStatus.SUCCESS.getCode());
        run.setScanned(importResult == null ? 0 : importResult.getTotalRows());
        run.setInserted(importResult == null ? 0 : importResult.getInsertedRows());
        run.setUpdated(importResult == null ? 0 : importResult.getUpdatedRows());
        run.setSkipped(0);
        run.setFailed(failed);
        run.setErrorMessage(null);
        finishRun(run);
        marketDataSyncRunMapper.updateById(run);
        MarketDataSyncResultDto result = toResult(run, selection, targetSymbol, startDate, endDate);
        if (selection.fallback() && StringUtils.hasText(selection.fallbackReason())) {
            result.getErrors().add(selection.fallbackReason());
        }
        appendRejectedErrors(importResult, result);
        return result;
    }

    private MarketDataSyncResultDto finishFailed(MarketDataSyncRun run, MarketDataProviderSelection selection, RuntimeException ex) {
        run.setStatus(MarketDataSyncStatus.FAILED.getCode());
        run.setFailed(1);
        run.setErrorMessage(ex.getMessage());
        finishRun(run);
        marketDataSyncRunMapper.updateById(run);
        MarketDataSyncResultDto result = toResult(run, selection, run.getTargetSymbol(), run.getStartDate(), run.getEndDate());
        if (StringUtils.hasText(ex.getMessage())) {
            result.getErrors().add(ex.getMessage());
        }
        return result;
    }

    private void finishRun(MarketDataSyncRun run) {
        LocalDateTime finishedAt = LocalDateTime.now();
        run.setFinishedAt(finishedAt);
        run.setDurationMs(Duration.between(run.getStartedAt(), finishedAt).toMillis());
    }

    private MarketDataSyncResultDto toResult(
            MarketDataSyncRun run,
            MarketDataProviderSelection selection,
            String targetSymbol,
            java.time.LocalDate startDate,
            java.time.LocalDate endDate) {
        MarketDataSyncResultDto result = new MarketDataSyncResultDto();
        result.setRunId(run.getId());
        result.setDataSource(selection.dataSource());
        result.setSyncType(run.getSyncType());
        result.setStatus(run.getStatus());
        result.setRequestParams(run.getRequestParams());
        result.setTargetSymbol(targetSymbol);
        result.setStartDate(startDate);
        result.setEndDate(endDate);
        result.setTriggerType(run.getTriggerType());
        result.setTriggerBy(run.getTriggerBy());
        result.setScanned(safeInt(run.getScanned()));
        result.setInserted(safeInt(run.getInserted()));
        result.setUpdated(safeInt(run.getUpdated()));
        result.setSkipped(safeInt(run.getSkipped()));
        result.setFailed(safeInt(run.getFailed()));
        result.setStartedAt(run.getStartedAt());
        result.setFinishedAt(run.getFinishedAt());
        result.setDurationMs(run.getDurationMs());
        return result;
    }

    private void appendRejectedErrors(MarketDataImportResultDto<?> importResult, MarketDataSyncResultDto result) {
        if (importResult == null || importResult.getRejectedRows().isEmpty()) {
            return;
        }
        importResult.getRejectedRows().forEach(row -> result.getErrors().add(row.getReason()));
    }

    private String requestParams(Object request, MarketDataProviderSelection selection) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("request", request);
        params.put("provider", selection.dataSource());
        params.put("fallback", selection.fallback());
        params.put("fallbackReason", selection.fallbackReason());
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private long pageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1L : pageNum;
    }

    private long pageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? 20L : pageSize;
    }
}
