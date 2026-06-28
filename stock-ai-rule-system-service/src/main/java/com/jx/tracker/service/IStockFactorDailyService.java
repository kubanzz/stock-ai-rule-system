package com.jx.tracker.service;

import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.vo.StockFactorDailyVo;

import java.time.LocalDate;
import java.util.List;

public interface IStockFactorDailyService {

    StockFactorDailyVo calculateAndSave(TechnicalFactorCalculateRequestDto request);

    List<StockFactorDailyVo> calculateAndSaveBatch(List<String> symbols, LocalDate tradeDate);
}
