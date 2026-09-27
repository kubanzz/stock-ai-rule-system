package com.jx.tracker.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.domain.dto.DailyWorkflowTriggerDto;
import com.jx.tracker.domain.vo.StockConsoleVo;
import com.jx.tracker.scheduler.DailyWorkflowOrchestrator;
import com.jx.tracker.scheduler.WorkflowTriggerType;
import com.jx.tracker.service.StockConsoleQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/scheduler")
@Tag(name = "调度与集成验收")
public class DailyWorkflowController {

    private final DailyWorkflowOrchestrator orchestrator;
    private final StockConsoleQueryService consoleQueryService;

    public DailyWorkflowController(DailyWorkflowOrchestrator orchestrator) {
        this(orchestrator, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DailyWorkflowController(DailyWorkflowOrchestrator orchestrator, StockConsoleQueryService consoleQueryService) {
        this.orchestrator = orchestrator;
        this.consoleQueryService = consoleQueryService;
    }

    @PostMapping("/daily-workflow")
    @Operation(summary = "手动触发每日股票辅助决策工作流")
    public AjaxResult triggerDailyWorkflow(@RequestBody(required = false) DailyWorkflowTriggerDto request) {
        return AjaxResult.success("每日工作流已触发", orchestrator.runDailyWorkflow(request, WorkflowTriggerType.MANUAL));
    }

    @GetMapping("/daily-workflow/latest")
    @Operation(summary = "查询每日工作流最近一次运行")
    public AjaxResult latestDailyWorkflow(@RequestParam(required = false) LocalDate date) {
        if (consoleQueryService == null) {
            return AjaxResult.success(new StockConsoleVo.RunCenterOverview(
                    date, "unavailable", List.of(), List.of()));
        }
        return AjaxResult.success(consoleQueryService.runCenterOverview(date));
    }

    @GetMapping("/integration-dependencies")
    @Operation(summary = "查看调度集成依赖清单")
    public AjaxResult integrationDependencies() {
        return AjaxResult.success(orchestrator.integrationDependencies());
    }
}
