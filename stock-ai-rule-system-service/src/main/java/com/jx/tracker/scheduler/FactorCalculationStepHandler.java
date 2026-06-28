package com.jx.tracker.scheduler;

import com.jx.tracker.service.IStockFactorDailyService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class FactorCalculationStepHandler implements DailyWorkflowStepHandler {

    private final IStockFactorDailyService stockFactorDailyService;

    public FactorCalculationStepHandler(IStockFactorDailyService stockFactorDailyService) {
        this.stockFactorDailyService = stockFactorDailyService;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.FACTOR_CALCULATION;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        var request = context.getRequest();
        var factors = stockFactorDailyService.calculateAndSaveBatch(request.getSymbols(), request.getTradeDate());
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "技术因子计算完成。",
                Map.of("factorCount", factors.size())
        );
    }
}
