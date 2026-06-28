package com.jx.tracker.scheduler;

import com.jx.tracker.signal.service.StockSignalService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class SignalGenerationStepHandler implements DailyWorkflowStepHandler {

    private final StockSignalService stockSignalService;

    public SignalGenerationStepHandler(StockSignalService stockSignalService) {
        this.stockSignalService = stockSignalService;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.SIGNAL_GENERATION;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        var request = context.getRequest();
        var signals = stockSignalService.generateDailySignalsFromFactors(request.getTradeDate(), request.getSymbols());
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "股票辅助决策信号生成完成。",
                Map.of("signalCount", signals.size())
        );
    }
}
