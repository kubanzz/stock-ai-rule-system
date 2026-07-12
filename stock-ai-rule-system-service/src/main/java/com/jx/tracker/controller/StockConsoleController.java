package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.service.StockConsoleQueryService;
import com.jx.tracker.service.StockDashboardQueryService;
import com.jx.tracker.service.StockWatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "股票规则控制台页面聚合接口")
public class StockConsoleController {

    private final StockConsoleQueryService stockConsoleQueryService;
    private final StockDashboardQueryService stockDashboardQueryService;
    private final StockWatchlistService stockWatchlistService;

    @GetMapping("/signals/dashboard")
    @Operation(summary = "查询信号看板聚合数据")
    public AjaxResult dashboard(@RequestParam(value = "date", required = false) LocalDate date,
                                @RequestParam(value = "market", required = false) String market,
                                @RequestParam(value = "poolCode", required = false) String poolCode,
                                @RequestParam(value = "symbol", required = false) String symbol,
                                @RequestParam(value = "signal", required = false) String signal,
                                @RequestParam(value = "industry", required = false) String industry,
                                @RequestParam(value = "confidenceMin", required = false) BigDecimal confidenceMin,
                                @RequestParam(value = "confidenceMax", required = false) BigDecimal confidenceMax,
                                @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                @RequestParam(value = "pageSize", defaultValue = "20") int pageSize,
                                @RequestParam(value = "sortField", required = false) String sortField,
                                @RequestParam(value = "sortOrder", required = false) String sortOrder) {
        StockConsoleVo.SignalDashboardQuery query = new StockConsoleVo.SignalDashboardQuery(
                date, market, poolCode, symbol, signal, industry, confidenceMin, confidenceMax,
                pageNum, pageSize, sortField, sortOrder
        );
        return AjaxResult.success(stockDashboardQueryService.dashboard(query));
    }

    @GetMapping("/watchlists")
    @Operation(summary = "查询股票池列表")
    public AjaxResult watchlists(@RequestParam(value = "market", required = false) String market) {
        return AjaxResult.success(stockWatchlistService.list(market));
    }

    @PostMapping("/watchlists")
    @Operation(summary = "创建股票池")
    public AjaxResult createWatchlist(@Valid @RequestBody StockConsoleVo.WatchlistMutationRequest request) {
        return AjaxResult.success(stockWatchlistService.create(request));
    }

    @PutMapping("/watchlists/{poolId}")
    @Operation(summary = "更新股票池")
    public AjaxResult updateWatchlist(@PathVariable("poolId") String poolId,
                                      @Valid @RequestBody StockConsoleVo.WatchlistMutationRequest request) {
        return AjaxResult.success(stockWatchlistService.update(poolId, request));
    }

    @DeleteMapping("/watchlists/{poolId}")
    @Operation(summary = "删除股票池")
    public AjaxResult deleteWatchlist(@PathVariable("poolId") String poolId) {
        stockWatchlistService.delete(poolId);
        return AjaxResult.success();
    }

    @PostMapping("/watchlists/{poolId}/stocks")
    @Operation(summary = "添加股票到股票池")
    public AjaxResult addWatchlistStock(@PathVariable("poolId") String poolId,
                                        @Valid @RequestBody StockConsoleVo.WatchlistStockMutationRequest request) {
        return AjaxResult.success(stockWatchlistService.addStock(poolId, request));
    }

    @GetMapping("/watchlists/{poolId}/stock-candidates")
    @Operation(summary = "分页搜索股票池候选股票")
    public PageResult<StockConsoleVo.WatchlistCandidate> stockCandidates(
            @PathVariable("poolId") String poolId,
            @RequestParam(value = "market", defaultValue = "A股") String market,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return stockWatchlistService.searchCandidates(poolId, market, keyword, pageNum, pageSize);
    }

    @PostMapping("/watchlists/{poolId}/stocks/batch")
    @Operation(summary = "批量添加股票到股票池")
    public AjaxResult addWatchlistStocks(
            @PathVariable("poolId") String poolId,
            @Valid @RequestBody StockConsoleVo.WatchlistBatchMutationRequest request) {
        return AjaxResult.success(stockWatchlistService.addStocks(poolId, request));
    }

    @DeleteMapping("/watchlists/{poolId}/stocks/{symbol}")
    @Operation(summary = "从股票池移除股票")
    public AjaxResult removeWatchlistStock(@PathVariable("poolId") String poolId,
                                           @PathVariable("symbol") String symbol) {
        return AjaxResult.success(stockWatchlistService.removeStock(poolId, symbol));
    }

    @GetMapping("/stocks/{symbol}/research")
    @Operation(summary = "查询股票研究详情")
    public AjaxResult research(@PathVariable("symbol") String symbol,
                               @RequestParam(value = "date", required = false) LocalDate date) {
        return AjaxResult.success(stockConsoleQueryService.research(symbol, date));
    }

    @GetMapping("/rules/governance")
    @Operation(summary = "查询规则治理聚合数据")
    public AjaxResult ruleGovernance(@RequestParam(value = "ruleType", required = false) String ruleType,
                                     @RequestParam(value = "status", required = false) String status,
                                     @RequestParam(value = "source", required = false) String source) {
        return AjaxResult.success(stockConsoleQueryService.ruleGovernance(ruleType, status, source));
    }

    @GetMapping("/rules/{ruleCode}/governance")
    @Operation(summary = "查询规则治理详情")
    public AjaxResult ruleGovernanceDetail(@PathVariable("ruleCode") String ruleCode) {
        return AjaxResult.success(stockConsoleQueryService.ruleGovernanceDetail(ruleCode));
    }

    @GetMapping("/backtests/reports")
    @Operation(summary = "查询回测报告列表与聚合表现")
    public AjaxResult backtestReports(@RequestParam(value = "objectCode", required = false) String objectCode,
                                      @RequestParam(value = "market", required = false) String market) {
        return AjaxResult.success(stockConsoleQueryService.backtestReports(objectCode, market));
    }

    @GetMapping("/backtests/reports/{reportId}")
    @Operation(summary = "查询回测报告详情")
    public AjaxResult backtestReport(@PathVariable("reportId") String reportId) {
        return AjaxResult.success(stockConsoleQueryService.backtestReport(reportId));
    }

    @GetMapping("/backtests/reports/{reportId}/failure-samples")
    @Operation(summary = "查询回测失败样本")
    public AjaxResult backtestFailureSamples(@PathVariable("reportId") String reportId) {
        return AjaxResult.success(stockConsoleQueryService.backtestFailureSamples(reportId));
    }

    @GetMapping("/ai/reviews/summary")
    @Operation(summary = "查询 AI 复盘概览")
    public AjaxResult aiReviewSummary(@RequestParam(value = "date", required = false) LocalDate date) {
        return AjaxResult.success(stockConsoleQueryService.aiReviewSummary(date));
    }

    @GetMapping("/ai/reviews/misjudgements")
    @Operation(summary = "查询 AI 复盘误判样本")
    public AjaxResult aiMisjudgements(@RequestParam(value = "date", required = false) LocalDate date,
                                      @RequestParam(value = "reasonCategory", required = false) String reasonCategory) {
        return AjaxResult.success(stockConsoleQueryService.aiMisjudgements(date, reasonCategory));
    }

    @PostMapping("/ai/reviews/misjudgements/{sampleId}/candidate-rule")
    @Operation(summary = "基于误判样本生成候选规则建议")
    public AjaxResult createCandidateFromMisjudgement(@PathVariable("sampleId") String sampleId) {
        return AjaxResult.success(stockConsoleQueryService.createCandidateFromMisjudgement(sampleId));
    }

    @GetMapping("/run-center/overview")
    @Operation(summary = "查询运行中心概览")
    public AjaxResult runCenterOverview(@RequestParam(value = "date", required = false) LocalDate date) {
        return AjaxResult.success(stockConsoleQueryService.runCenterOverview(date));
    }
}
