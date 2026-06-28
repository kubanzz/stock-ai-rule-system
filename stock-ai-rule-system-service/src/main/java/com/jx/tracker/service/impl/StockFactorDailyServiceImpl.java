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
import com.jx.tracker.mapper.StockDailyQuoteMapper;
import com.jx.tracker.mapper.StockFactorDailyMapper;
import com.jx.tracker.service.IStockFactorDailyService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

@Service
public class StockFactorDailyServiceImpl implements IStockFactorDailyService {

    private static final int LOOKBACK_LIMIT = 80;

    private final StockDailyQuoteMapper quoteMapper;
    private final StockFactorDailyMapper factorMapper;
    private final TechnicalFactorCalculator calculator;
    private final ObjectMapper objectMapper;

    public StockFactorDailyServiceImpl(
            StockDailyQuoteMapper quoteMapper,
            StockFactorDailyMapper factorMapper,
            TechnicalFactorCalculator calculator,
            ObjectMapper objectMapper
    ) {
        this.quoteMapper = quoteMapper;
        this.factorMapper = factorMapper;
        this.calculator = calculator;
        this.objectMapper = objectMapper;
    }

    @Override
    public StockFactorDailyVo calculateAndSave(TechnicalFactorCalculateRequestDto request) {
        validateRequest(request);
        List<StockDailyQuote> quotes = quoteMapper.selectList(Wrappers.<StockDailyQuote>lambdaQuery()
                .eq(StockDailyQuote::getSymbol, request.getSymbol())
                .le(StockDailyQuote::getTradeDate, request.getTradeDate())
                .orderByDesc(StockDailyQuote::getTradeDate)
                .last("LIMIT " + LOOKBACK_LIMIT));

        TechnicalFactorResult result = calculator.calculate(request.getSymbol(), request.getTradeDate(), quotes);
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

    private String toJson(TechnicalFactorResult result) {
        try {
            return objectMapper.writeValueAsString(result.factors());
        } catch (JsonProcessingException e) {
            throw new ServiceException("技术因子序列化失败");
        }
    }
}
