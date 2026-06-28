package com.jx.tracker.backtest;

import com.jx.tracker.domain.dto.BacktestRequestDto;
import com.jx.tracker.domain.entity.BacktestResult;

public interface BacktestService {

    BacktestResult runRuleBacktest(BacktestRequestDto request);

    BacktestResult runCandidateRuleBacktest(BacktestRequestDto request);
}
