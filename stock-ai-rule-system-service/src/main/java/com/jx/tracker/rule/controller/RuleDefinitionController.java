package com.jx.tracker.rule.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.RuleDefinitionUpsertDto;
import com.jx.tracker.rule.service.RuleDefinitionService;
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
@RequestMapping("/api/rules")
@Tag(name = "规则定义管理")
public class RuleDefinitionController {

    private final RuleDefinitionService ruleDefinitionService;

    public RuleDefinitionController(RuleDefinitionService ruleDefinitionService) {
        this.ruleDefinitionService = ruleDefinitionService;
    }

    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String ruleFormat) {
        return AjaxResult.success(ruleDefinitionService.list(status, ruleFormat));
    }

    @PostMapping
    public AjaxResult create(@RequestBody RuleDefinitionUpsertDto dto) {
        return AjaxResult.success(ruleDefinitionService.create(dto));
    }

    @PutMapping("/{ruleCode}")
    public AjaxResult update(@PathVariable String ruleCode, @RequestBody RuleDefinitionUpsertDto dto) {
        return AjaxResult.success(ruleDefinitionService.update(ruleCode, dto));
    }

    @PostMapping("/{ruleCode}/enable")
    public AjaxResult enable(@PathVariable String ruleCode) {
        return AjaxResult.success(ruleDefinitionService.enable(ruleCode));
    }

    @PostMapping("/{ruleCode}/disable")
    public AjaxResult disable(@PathVariable String ruleCode) {
        return AjaxResult.success(ruleDefinitionService.disable(ruleCode));
    }
}
