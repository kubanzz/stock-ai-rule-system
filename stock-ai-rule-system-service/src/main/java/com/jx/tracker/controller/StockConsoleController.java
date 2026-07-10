package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.service.StockConsoleQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "股票规则控制台页面聚合接口")
public class StockConsoleController {

    private final StockConsoleQueryService stockConsoleQueryService;

    @GetMapping("/signals/dashboard")
    @Operation(summary = "查询信号看板聚合数据")
    public AjaxResult dashboard(@RequestParam(value = "date", required = false) LocalDate date,
                                @RequestParam(value = "market", required = false) String market,
                                @RequestParam(value = "poolCode", required = false) String poolCode) {
        return AjaxResult.success(stockConsoleQueryService.dashboard(date, market, poolCode));
    }

    @GetMapping("/watchlists")
    @Operation(summary = "查询股票池列表")
    public AjaxResult watchlists(@RequestParam(value = "market", required = false) String market) {
        return AjaxResult.success(stockConsoleQueryService.watchlists(market));
    }

    @PostMapping("/watchlists/{poolId}/stocks")
    @Operation(summary = "添加股票到股票池")
    public AjaxResult addWatchlistStock(@PathVariable("poolId") String poolId,
                                        @RequestBody StockConsoleVo.WatchlistStockMutationRequest request) {
        return AjaxResult.success(stockConsoleQueryService.addWatchlistStock(poolId, request));
    }

    @DeleteMapping("/watchlists/{poolId}/stocks/{symbol}")
    @Operation(summary = "从股票池移除股票")
    public AjaxResult removeWatchlistStock(@PathVariable("poolId") String poolId,
                                           @PathVariable("symbol") String symbol) {
        return AjaxResult.success(stockConsoleQueryService.removeWatchlistStock(poolId, symbol));
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
