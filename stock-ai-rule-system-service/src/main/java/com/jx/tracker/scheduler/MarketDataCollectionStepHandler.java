package com.jx.tracker.scheduler;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
public class MarketDataCollectionStepHandler implements DailyWorkflowStepHandler {

    private final StockDailyQuoteMapper stockDailyQuoteMapper;

    public MarketDataCollectionStepHandler(StockDailyQuoteMapper stockDailyQuoteMapper) {
        this.stockDailyQuoteMapper = stockDailyQuoteMapper;
    }

    @Override
    public WorkflowStepCode stepCode() {
        return WorkflowStepCode.MARKET_DATA_COLLECTION;
    }

    @Override
    public com.jx.tracker.domain.vo.DailyWorkflowStepResultVo execute(DailyWorkflowContext context) {
        LocalDateTime startedAt = LocalDateTime.now();
        var request = context.getRequest();
        Long quoteCount = stockDailyQuoteMapper.selectCount(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getTradeDate, request.getTradeDate())
                .in(request.getSymbols() != null && !request.getSymbols().isEmpty(), StockDailyQuote::getSymbol, request.getSymbols()));
        return DailyWorkflowStepResults.success(
                stepCode(),
                startedAt,
                "已检查本地日 K 行情快照。",
                Map.of("quoteCount", quoteCount)
        );
    }
}
