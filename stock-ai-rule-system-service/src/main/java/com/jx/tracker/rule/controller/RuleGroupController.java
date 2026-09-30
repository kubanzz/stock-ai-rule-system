package com.jx.tracker.rule.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.domain.dto.RuleCompositionCopyDto;
import com.jx.tracker.domain.dto.RuleCompositionStatusDto;
import com.jx.tracker.domain.dto.RuleGroupDetailDto;
import com.jx.tracker.rule.service.RuleGroupService;
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
@RequestMapping("/api/rule-groups")
@Tag(name = "规则组管理")
public class RuleGroupController {
    private final RuleGroupService service;

    public RuleGroupController(RuleGroupService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "查询规则组列表")
    public PageResult<RuleGroupDetailDto> list(@RequestParam(name = "status", required = false) String status) {
        var rows = service.listGroups(status);
        return PageResult.getDataTable(rows, (long) rows.size());
    }

    @GetMapping("/{code}")
    @Operation(summary = "查询规则组详情")
    public AjaxResult get(@PathVariable("code") String code) {
        return AjaxResult.success(service.getGroup(code));
    }

    @GetMapping("/{code}/versions")
    @Operation(summary = "查询规则组历史版本")
    public AjaxResult versions(@PathVariable("code") String code) {
        return AjaxResult.success(service.listVersions(code));
    }

    @GetMapping("/{code}/versions/{versionNo}")
    @Operation(summary = "查询规则组指定版本")
    public AjaxResult version(@PathVariable("code") String code, @PathVariable("versionNo") String versionNo) {
        return AjaxResult.success(service.getVersion(code, versionNo));
    }

    @PostMapping
    @Operation(summary = "创建规则组")
    public AjaxResult create(@RequestBody RuleGroupDetailDto request) {
        return AjaxResult.success(service.create(request));
    }

    @PutMapping("/{code}")
    @Operation(summary = "更新规则组并保存新版本")
    public AjaxResult update(@PathVariable("code") String code, @RequestBody RuleGroupDetailDto request) {
        return AjaxResult.success(service.update(code, request));
    }

    @PostMapping("/{code}/copy")
    @Operation(summary = "复制规则组")
    public AjaxResult copy(@PathVariable("code") String code, @RequestBody RuleCompositionCopyDto request) {
        return AjaxResult.success(service.copy(code, request.getNewCode(), request.getNewName()));
    }

    @PutMapping("/{code}/status")
    @Operation(summary = "修改规则组状态")
    public AjaxResult changeStatus(@PathVariable("code") String code, @RequestBody RuleCompositionStatusDto request) {
        return AjaxResult.success(service.changeStatus(code, request.getStatus()));
    }
}
