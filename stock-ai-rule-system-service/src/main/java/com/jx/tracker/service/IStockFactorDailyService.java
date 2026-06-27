package com.jx.tracker.service;

import com.jx.tracker.domain.dto.TechnicalFactorCalculateRequestDto;
import com.jx.tracker.domain.vo.StockFactorDailyVo;

public interface IStockFactorDailyService {

    StockFactorDailyVo calculateAndSave(TechnicalFactorCalculateRequestDto request);
}
