package com.jx.tracker.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.entity.StockDailyQuote;
import com.jx.tracker.domain.entity.StockFactorDaily;
import com.jx.tracker.domain.vo.StockFactorDailyVo;
import com.jx.tracker.exception.ServiceException;
import com.jx.tracker.factor.TechnicalFactorCalculator;
import com.jx.tracker.factor.TechnicalFactorResult;
import com.jx.tracker.factor.ResearchReboundFactorCalculator;
import com.jx.tracker.factor.P4VoteFactorCalculator;
import com.jx.tracker.domain.entity.StockBase;
import com.jx.tracker.mapper.StockBaseMapper;
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.service.IStockFactorDailyService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class StockFactorDailyServiceImpl implements IStockFactorDailyService {

    private static final int LOOKBACK_LIMIT = 80;

    private final StockDailyQuoteMapper quoteMapper;
    private final StockFactorDailyMapper factorMapper;
    private final TechnicalFactorCalculator calculator;
    private final ObjectMapper objectMapper;
    private final ResearchReboundFactorCalculator reboundCalculator;
    private final P4VoteFactorCalculator p4VoteCalculator;
    private final StockBaseMapper stockBaseMapper;

    public StockFactorDailyServiceImpl(
            StockDailyQuoteMapper quoteMapper,
            StockFactorDailyMapper factorMapper,
            TechnicalFactorCalculator calculator,
            ObjectMapper objectMapper
    ) {
        this(quoteMapper, factorMapper, calculator, objectMapper, new ResearchReboundFactorCalculator());
    }

    public StockFactorDailyServiceImpl(StockDailyQuoteMapper quoteMapper,
                                      StockFactorDailyMapper factorMapper,
                                      TechnicalFactorCalculator calculator,
                                      ObjectMapper objectMapper,
                                      ResearchReboundFactorCalculator reboundCalculator) {
        this(quoteMapper, factorMapper, calculator, objectMapper, reboundCalculator, null, null);
    }

    @Autowired
    public StockFactorDailyServiceImpl(StockDailyQuoteMapper quoteMapper,
                                      StockFactorDailyMapper factorMapper,
                                      TechnicalFactorCalculator calculator,
                                      ObjectMapper objectMapper,
                                      ResearchReboundFactorCalculator reboundCalculator,
                                      P4VoteFactorCalculator p4VoteCalculator,
                                      StockBaseMapper stockBaseMapper) {
        this.quoteMapper = quoteMapper;
        this.factorMapper = factorMapper;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
        this.reboundCalculator = reboundCalculator;
        this.p4VoteCalculator = p4VoteCalculator;
        this.stockBaseMapper = stockBaseMapper;
    }

    @Override
    public StockFactorDailyVo calculateAndSave(TechnicalFactorCalculateRequestDto request) {
        validateRequest(request);
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, request.getSymbol())
                .le(StockDailyQuote::getTradeDate, request.getTradeDate())
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last("LIMIT " + LOOKBACK_LIMIT));

        TechnicalFactorResult result = withReboundFactors(
                calculator.calculate(request.getSymbol(), request.getTradeDate(), quotes));
        String factorJson = toJson(result);
        StockFactorDaily existing = factorMapper.selectOne(Wrappers.<StockFactorDaily>lambdaQuery()
                .eq(StockFactorDaily::getSymbol, request.getSymbol())
                .eq(StockFactorDaily::getTradeDate, request.getTradeDate()));

        StockFactorDaily entity = StockFactorDaily.builder()
                .id(existing == null ? null : existing.getId())
                .symbol(result.symbol())
                .tradeDate(result.tradeDate())
                .factorJson(factorJson)
                .build();
        if (existing == null) {
            factorMapper.insert(entity);
        } else {
            factorMapper.updateById(entity);
        }

        return StockFactorDailyVo.builder()
                .symbol(result.symbol())
                .tradeDate(result.tradeDate())
                .factors(result.factors())
                .build();
    }

    /** Historical catch-up excludes simulated rows from the full 80-bar factor input. */
    @Override
    public StockFactorDailyVo calculateAndSaveFromRealQuotes(TechnicalFactorCalculateRequestDto request) {
        validateRequest(request);
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, request.getSymbol())
                .le(StockDailyQuote::getTradeDate, request.getTradeDate())
                .isNotNull(StockDailyQuote::getDataSource)
                .ne(StockDailyQuote::getDataSource, "mock")
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last("LIMIT " + LOOKBACK_LIMIT));
        // This method is only called once the backfill service has validated the
        // last 26 dates and all required target fields.
        TechnicalFactorResult result = withReboundFactors(
                calculator.calculate(request.getSymbol(), request.getTradeDate(), quotes));
        String factorJson = toJson(result);
        StockFactorDaily existing = factorMapper.selectOne(Wrappers.<StockFactorDaily>lambdaQuery()
                .eq(StockFactorDaily::getSymbol, request.getSymbol())
                .eq(StockFactorDaily::getTradeDate, request.getTradeDate()));
        StockFactorDaily entity = StockFactorDaily.builder()
                .id(existing == null ? null : existing.getId())
                .symbol(result.symbol())
                .tradeDate(result.tradeDate())
                .factorJson(factorJson)
                .build();
        if (existing == null) {
            factorMapper.insert(entity);
        } else {
            factorMapper.updateById(entity);
        }
        return StockFactorDailyVo.builder().symbol(result.symbol())
                .tradeDate(result.tradeDate()).factors(result.factors()).build();
    }

    @Override
    public List<StockFactorDailyVo> calculateAndSaveBatch(List<String> symbols, LocalDate tradeDate) {
        if (tradeDate == null) {
            throw new ServiceException("目标交易日不能为空");
        }
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        return symbols.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(symbol -> calculateAndSave(TechnicalFactorCalculateRequestDto.builder()
                        .symbol(symbol)
                        .tradeDate(tradeDate)
                        .build()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static void validateRequest(TechnicalFactorCalculateRequestDto request) {
        if (request == null || !StringUtils.hasText(request.getSymbol()) || request.getTradeDate() == null) {
            throw new ServiceException("股票代码和目标交易日不能为空");
        }
        if (request.getTradeDate().isAfter(LocalDate.now())) {
            throw new ServiceException("目标交易日不能晚于当前日期");
        }
    }

    private TechnicalFactorResult withReboundFactors(TechnicalFactorResult result) {
        List<StockDailyQuote> stockHistory = List.of();
        List<StockDailyQuote> benchmarkHistory = List.of();
        boolean reboundMember = reboundCalculator.isMember(result.symbol());
        boolean p4Member = p4VoteCalculator != null && p4VoteCalculator.isMember(result.symbol());
        if (reboundMember || p4Member) {
            // Research eligibility counts valid history before applying its normal-stock filters.
            // Keep legacy indicators on their original 80-bar window; never truncate this to 80.
            stockHistory = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                    .eq(StockDailyQuote::getSymbol, result.symbol())
                    .le(StockDailyQuote::getTradeDate, result.tradeDate())
                    .orderByAsc(StockDailyQuote::getTradeDate));
            benchmarkHistory = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                    .eq(StockDailyQuote::getSymbol, ResearchReboundFactorCalculator.BENCHMARK_SYMBOL)
                    .le(StockDailyQuote::getTradeDate, result.tradeDate())
                    .orderByAsc(StockDailyQuote::getTradeDate));
        }
        Map<String, Object> factors = new LinkedHashMap<>(result.factors());
        factors.putAll(reboundCalculator.calculate(result.symbol(), result.tradeDate(),
                stockHistory, benchmarkHistory));
        if (p4VoteCalculator != null) {
            StockBase currentStock = p4Member && stockBaseMapper != null
                    ? stockBaseMapper.selectOne(Wrappers.<StockBase>lambdaQuery()
                    .eq(StockBase::getSymbol, result.symbol())) : null;
            factors.putAll(p4VoteCalculator.calculate(result.symbol(), result.tradeDate(),
                    stockHistory, benchmarkHistory, currentStock));
        }
        return new TechnicalFactorResult(result.symbol(), result.tradeDate(), factors);
    }

    private String toJson(TechnicalFactorResult result) {
        try {
            return objectMapper.writeValueAsString(result.factors());
        } catch (JsonProcessingException e) {
            throw new ServiceException("技术因子序列化失败");
        }
    }
}
