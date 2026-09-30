package com.jx.tracker.controller;

import com.jx.tracker.backtest.SingleRuleBacktestService;
import com.jx.tracker.backtest.HistoricalBacktestQuotePreparationService;
import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.BacktestRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/backtests")
@Tag(name = "规则与组合回测")
public class BacktestController {

    private final SingleRuleBacktestService backtestService;
    private final HistoricalBacktestQuotePreparationService quotePreparationService;

    public BacktestController(SingleRuleBacktestService backtestService,
                              HistoricalBacktestQuotePreparationService quotePreparationService) {
        this.backtestService = backtestService;
        this.quotePreparationService = quotePreparationService;
    }

    @PostMapping
    @Operation(summary = "执行规则、规则组或应用方案回测并保存结果")
    public AjaxResult runBacktest(@RequestBody BacktestRequestDto request) {
        quotePreparationService.prepare(request);
        return AjaxResult.success(backtestService.runSingleRuleBacktest(request));
    }
}
