package com.jx.tracker.risk.dashboard;

import com.jx.tracker.risk.model.GateDecision;
import com.jx.tracker.risk.model.RiskSnapshot;

/** 信号看板单行所需的只读风险附加信息。 */
public record StockDashboardRiskOverlay(
        RiskSnapshot snapshot,
        GateDecision gateDecision
) {
}
