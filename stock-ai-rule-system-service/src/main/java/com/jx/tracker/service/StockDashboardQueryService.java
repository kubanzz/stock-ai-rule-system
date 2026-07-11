package com.jx.tracker.service;

import com.jx.tracker.domain.vo.StockConsoleVo;

public interface StockDashboardQueryService {

    StockConsoleVo.SignalDashboardOverview dashboard(StockConsoleVo.SignalDashboardQuery query);
}
