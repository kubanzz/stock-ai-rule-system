package com.jx.tracker.risk.controller;

import com.jx.tracker.common.AjaxResult;
import com.jx.tracker.common.PageResult;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.risk.query.RiskAssessmentQueryService;
import com.jx.tracker.risk.query.dto.RiskAssessmentDto.RiskObjectListItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/risks")
@Tag(name = "风险评估")
public class RiskAssessmentController {

    private final RiskAssessmentQueryService queryService;

    public RiskAssessmentController(RiskAssessmentQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/overview")
    @Operation(summary = "查询风险总览")
    public AjaxResult overview(
            @RequestParam(value = "horizon", required = false) String horizon,
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate
    ) {
        return AjaxResult.success(queryService.overview(horizon, tradeDate));
    }

    @GetMapping("/objects")
    @Operation(summary = "分页查询风险对象")
    public PageResult<RiskObjectListItem> objects(
            @RequestParam(value = "objectType", required = false) String objectType,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "horizon", required = false) String horizon,
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize
    ) {
        validatePage(pageNum, pageSize);
        return queryService.listObjects(
                objectType, level, horizon, tradeDate, keyword, pageNum, pageSize
        );
    }

    @GetMapping("/objects/{objectType}/{objectId}")
    @Operation(summary = "查询风险对象详情")
    public AjaxResult objectDetail(
            @PathVariable("objectType") String objectType,
            @PathVariable("objectId") String objectId,
            @RequestParam(value = "horizon", required = false) String horizon,
            @RequestParam(value = "tradeDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate tradeDate
    ) {
        return AjaxResult.success(queryService.objectDetail(objectType, objectId, horizon, tradeDate));
    }

    @GetMapping("/objects/{objectType}/{objectId}/trend")
    @Operation(summary = "查询风险对象趋势")
    public AjaxResult trend(
            @PathVariable("objectType") String objectType,
            @PathVariable("objectId") String objectId,
            @RequestParam(value = "horizon", required = false) String horizon,
            @RequestParam(value = "startDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new ServiceException("startDate 不能晚于 endDate", 400);
        }
        return AjaxResult.success(queryService.trend(
                objectType, objectId, horizon, startDate, endDate
        ));
    }

    private void validatePage(int pageNum, int pageSize) {
        if (pageNum < 1) {
            throw new ServiceException("pageNum 必须大于等于 1", 400);
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new ServiceException("pageSize 必须在 1 到 100 之间", 400);
        }
    }
}
