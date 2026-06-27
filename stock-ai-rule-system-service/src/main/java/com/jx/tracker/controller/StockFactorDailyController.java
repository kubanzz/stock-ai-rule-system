package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.service.IStockFactorDailyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/factors")
@Tag(name = "股票因子计算")
public class StockFactorDailyController {

    private final IStockFactorDailyService stockFactorDailyService;

    public StockFactorDailyController(IStockFactorDailyService stockFactorDailyService) {
        this.stockFactorDailyService = stockFactorDailyService;
    }

    @PostMapping("/technical/calculate")
    @Operation(summary = "计算并保存单只股票技术因子")
    public AjaxResult calculateTechnicalFactors(@RequestBody TechnicalFactorCalculateRequestDto request) {
        return AjaxResult.success(stockFactorDailyService.calculateAndSave(request));
    }
}
