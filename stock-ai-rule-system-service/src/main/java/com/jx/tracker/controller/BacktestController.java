package com.jx.tracker.controller;

import com.jx.tracker.backtest.SingleRuleBacktestService;
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
@Tag(name = "单规则回测")
public class BacktestController {

    private final SingleRuleBacktestService backtestService;

    public BacktestController(SingleRuleBacktestService backtestService) {
        this.backtestService = backtestService;
    }

    @PostMapping
    @Operation(summary = "执行单规则回测并保存结果")
    public AjaxResult runBacktest(@RequestBody BacktestRequestDto request) {
        return AjaxResult.success(backtestService.runSingleRuleBacktest(request));
    }
}
