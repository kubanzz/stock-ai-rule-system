package com.jx.tracker.verification;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jx.tracker.domain.entity.StockActualResult;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockSignalDaily;
import com.jx.tracker.mapper.StockActualResultMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockSignalDailyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class StockActualResultService {

    private final StockSignalDailyMapper signalMapper;
    private final StockDailyQuoteMapper quoteMapper;
    private final StockActualResultMapper actualResultMapper;
    private final PredictionHitPolicy hitPolicy;

    public StockActualResultService(StockSignalDailyMapper signalMapper,
                                    StockDailyQuoteMapper quoteMapper,
                                    StockActualResultMapper actualResultMapper,
                                    PredictionHitPolicy hitPolicy) {
        this.signalMapper = signalMapper;
        this.quoteMapper = quoteMapper;
        this.actualResultMapper = actualResultMapper;
        this.hitPolicy = hitPolicy;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<StockActualResult> verifySignals(LocalDate startDate, LocalDate endDate) {
        List<StockSignalDaily> signals = signalMapper.selectList(Wrappers.<StockSignalDaily>lambdaQuery()
                .ge(StockSignalDaily::getSignalDate, startDate)
                .le(StockSignalDaily::getSignalDate, endDate)
                .orderByAsc(StockSignalDaily::getSignalDate));

        List<StockActualResult> results = new ArrayList<>();
        for (StockSignalDaily signal : signals) {
            StockActualResult result = buildActualResult(signal);
            actualResultMapper.insert(result);
            results.add(result);
        }
        return results;
    }

    private StockActualResult buildActualResult(StockSignalDaily signal) {
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, signal.getSymbol())
                .ge(StockDailyQuote::getTradeDate, signal.getSignalDate())
                .orderByAsc(StockDailyQuote::getTradeDate));

        BigDecimal return1d = forwardReturn(quotes, 1);
        BigDecimal return3d = forwardReturn(quotes, 3);
        BigDecimal return5d = forwardReturn(quotes, 5);
        BigDecimal return10d = forwardReturn(quotes, 10);

        return StockActualResult.builder()
                .symbol(signal.getSymbol())
                .signalDate(signal.getSignalDate())
                .return1d(return1d)
                .return3d(return3d)
                .return5d(return5d)
                .return10d(return10d)
                .hit1d(hitPolicy.isHit(signal.getSignal(), return1d))
                .hit5d(hitPolicy.isHit(signal.getSignal(), return5d))
                .build();
    }

    private BigDecimal forwardReturn(List<StockDailyQuote> quotes, int holdingDays) {
        if (quotes.size() <= holdingDays || quotes.get(0).getClosePrice() == null || quotes.get(holdingDays).getClosePrice() == null) {
            return null;
        }
        BigDecimal baseClose = quotes.get(0).getClosePrice();
        if (baseClose.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return quotes.get(holdingDays).getClosePrice()
                .subtract(baseClose)
                .divide(baseClose, 4, RoundingMode.HALF_UP);
    }
}
