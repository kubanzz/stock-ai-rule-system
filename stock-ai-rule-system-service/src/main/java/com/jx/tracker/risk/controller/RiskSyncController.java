package com.jx.tracker.risk.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.risk.sync.RiskSyncJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/risks/sync")
@ConditionalOnProperty(
        prefix = "stock-ai-rule.risk-warning",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Tag(name = "风险数据同步")
public class RiskSyncController {

    private final RiskSyncJobService service;

    public RiskSyncController(RiskSyncJobService service) {
        this.service = service;
    }

    @PostMapping("/market")
    @Operation(summary = "异步同步最新市场与申万行业风险数据")
    public AjaxResult syncMarket() {
        return AjaxResult.success(service.startMarketSync());
    }

    @PostMapping("/stocks/{symbol}")
    @Operation(summary = "异步同步市场、所属行业和股票风险数据")
    public AjaxResult syncStock(@PathVariable("symbol") String symbol) {
        return AjaxResult.success(service.startStockSync(symbol));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "查询风险同步任务")
    public AjaxResult job(@PathVariable("jobId") String jobId) {
        return AjaxResult.success(service.get(jobId)
                .orElseThrow(() -> new ServiceException("同步任务不存在或已过期", 404)));
    }

    @GetMapping("/status")
    @Operation(summary = "查询风险同步状态")
    public AjaxResult status() {
        return AjaxResult.success(service.status());
    }
}
