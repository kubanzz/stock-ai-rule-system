package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.vo.SignalBackfillRunVo;
import com.jx.tracker.service.SignalBackfillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/signals/backfill-runs")
@RequiredArgsConstructor
@Tag(name = "关注股票行情和信号补齐")
public class SignalBackfillController {

    private final SignalBackfillService service;

    @PostMapping
    @Operation(summary = "异步补齐我的关注 A 股行情并计算缺失信号")
    public AjaxResult start() {
        return AjaxResult.success("补齐任务已启动", service.start());
    }

    @GetMapping("/latest")
    @Operation(summary = "查询最近一次补齐任务，供页面刷新后恢复进度")
    public AjaxResult latest() {
        return AjaxResult.success(service.latest());
    }

    @GetMapping("/{runId}")
    @Operation(summary = "查询补齐任务进度与缺口")
    public AjaxResult get(@PathVariable String runId) {
        SignalBackfillRunVo run = service.get(runId);
        return run == null ? AjaxResult.error("补齐任务不存在") : AjaxResult.success(run);
    }
}
