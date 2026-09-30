package com.jx.tracker.rule.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.dto.RuleCompositionCopyDto;
import com.jx.tracker.domain.dto.RuleCompositionStatusDto;
import com.jx.tracker.domain.dto.RuleStrategyDetailDto;
import com.jx.tracker.rule.service.RuleStrategyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rule-strategies")
@Tag(name = "规则应用方案管理")
public class RuleStrategyController {
    private final RuleStrategyService service;

    public RuleStrategyController(RuleStrategyService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "查询应用方案列表")
    public PageResult<RuleStrategyDetailDto> list(@RequestParam(name = "status", required = false) String status) {
        var rows = service.listStrategies(status);
        return PageResult.getDataTable(rows, (long) rows.size());
    }

    @GetMapping("/active")
    @Operation(summary = "查询当前启用的应用方案")
    public AjaxResult active() {
        return AjaxResult.success(service.getActiveStrategy());
    }

    @GetMapping("/{code}")
    @Operation(summary = "查询应用方案详情")
    public AjaxResult get(@PathVariable("code") String code) {
        return AjaxResult.success(service.getStrategy(code));
    }

    @GetMapping("/{code}/versions")
    @Operation(summary = "查询应用方案历史版本")
    public AjaxResult versions(@PathVariable("code") String code) {
        return AjaxResult.success(service.listVersions(code));
    }

    @GetMapping("/{code}/versions/{versionNo}")
    @Operation(summary = "查询应用方案指定版本")
    public AjaxResult version(@PathVariable("code") String code, @PathVariable("versionNo") String versionNo) {
        return AjaxResult.success(service.getVersion(code, versionNo));
    }

    @PostMapping
    @Operation(summary = "创建应用方案")
    public AjaxResult create(@RequestBody RuleStrategyDetailDto request) {
        return AjaxResult.success(service.create(request));
    }

    @PutMapping("/{code}")
    @Operation(summary = "更新应用方案并保存新版本")
    public AjaxResult update(@PathVariable("code") String code, @RequestBody RuleStrategyDetailDto request) {
        return AjaxResult.success(service.update(code, request));
    }

    @PostMapping("/{code}/copy")
    @Operation(summary = "复制应用方案")
    public AjaxResult copy(@PathVariable("code") String code, @RequestBody RuleCompositionCopyDto request) {
        return AjaxResult.success(service.copy(code, request.getNewCode(), request.getNewName()));
    }

    @PutMapping("/{code}/status")
    @Operation(summary = "修改应用方案状态")
    public AjaxResult changeStatus(@PathVariable("code") String code, @RequestBody RuleCompositionStatusDto request) {
        return AjaxResult.success(service.changeStatus(code, request.getStatus()));
    }
}
