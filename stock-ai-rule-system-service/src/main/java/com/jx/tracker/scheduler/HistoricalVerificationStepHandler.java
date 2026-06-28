package com.jx.tracker.scheduler;

import com.jx.tracker.verification.StockActualResultService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class HistoricalVerificationStepHandler implements DailyWorkflowStepHandler {

    private final StockActualResultService stockActualResultService;

    public HistoricalVerificationStepHandler(StockActualResultService stockActualResultService) {
        this.stockActualResultService = stockActualResultService;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.HISTORICAL_VERIFICATION;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        var tradeDate = context.getRequest().getTradeDate();
        var results = stockActualResultService.verifySignals(tradeDate, tradeDate);
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "预测结果验证完成。",
                Map.of("actualResultCount", results.size())
        );
    }
}
