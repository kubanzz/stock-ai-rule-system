package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.ActualResultVerificationRequestDto;
import com.jx.tracker.verification.StockActualResultService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/verification/actual-results")
@Tag(name = "预测结果验证")
public class StockActualResultController {

    private final StockActualResultService actualResultService;

    public StockActualResultController(StockActualResultService actualResultService) {
        this.actualResultService = actualResultService;
    }

    @PostMapping
    @Operation(summary = "写入实际表现并计算预测命中")
    public AjaxResult verifyActualResults(@RequestBody ActualResultVerificationRequestDto request) {
        return AjaxResult.success(actualResultService.verifySignals(request.getStartDate(), request.getEndDate()));
    }
}
