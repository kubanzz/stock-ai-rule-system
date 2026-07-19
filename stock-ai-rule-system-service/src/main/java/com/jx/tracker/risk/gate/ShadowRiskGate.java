package com.jx.tracker.risk.gate;

import com.jx.tracker.risk.model.GateDecision;
import com.jx.tracker.risk.model.RiskGateStatus;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.SignalDirection;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 首轮只输出建议，永远不执行闸门，也不改写正式信号方向。 */
public final class ShadowRiskGate {

    private static final BigDecimal MARKET_WARNING_REDUCTION = new BigDecimal("0.15");
    private static final BigDecimal SECTOR_CRITICAL_REDUCTION = new BigDecimal("0.20");
    private static final BigDecimal SECTOR_BLOCK_CONFIDENCE = new BigDecimal("0.70");

    public Optional<ShadowGateResult> evaluate(
            RiskSignalCandidate signal,
            List<RiskSnapshot> snapshots,
            LocalDateTime calculatedAt
    ) {
        if (signal == null || calculatedAt == null) {
            throw new IllegalArgumentException("signal and calculatedAt are required");
        }
        List<RiskSnapshot> eligible = snapshots == null ? List.of() : snapshots.stream()
                .filter(snapshot -> formal(snapshot, signal, calculatedAt))
                .toList();
        return eligible.stream()
                .map(snapshot -> decision(signal, snapshot, calculatedAt))
                .max(Comparator
                        .comparingInt((ShadowGateResult result) -> actionRank(result.decision().suggestedAction()))
                        .thenComparing(result -> result.decision().suggestedConfidence(), Comparator.reverseOrder())
                        .thenComparingInt(result -> objectRank(result.snapshot().object().objectType())));
    }

    private boolean formal(RiskSnapshot snapshot, RiskSignalCandidate signal, LocalDateTime calculatedAt) {
        return snapshot != null
                && snapshot.horizon() == signal.horizon()
                && snapshot.tradeDate().equals(signal.tradeDate())
                && signal.relevantRiskObjects().contains(snapshot.object())
                && !snapshot.calculatedAt().isAfter(calculatedAt)
                && snapshot.totalScore() != null
                && snapshot.level() != null
                && snapshot.stage() != null
                && snapshot.riskConfidence() != null
                && snapshot.completeness().compareTo(new BigDecimal("0.80")) >= 0;
    }

    private ShadowGateResult decision(
            RiskSignalCandidate signal,
            RiskSnapshot snapshot,
            LocalDateTime calculatedAt
    ) {
        RiskGateStatus action;
        BigDecimal suggestedConfidence = signal.originalConfidence();
        String reason;
        if (signal.direction() != SignalDirection.BULLISH) {
            action = RiskGateStatus.NOTICE;
            reason = "看跌或观望信号仅附加风险说明，方向与置信度保持不变";
        } else if (completeCriticalStock(snapshot)) {
            action = RiskGateStatus.BLOCK;
            suggestedConfidence = BigDecimal.ZERO;
            reason = "个股完整红色风险门控，建议阻断看涨信号";
        } else if (snapshot.object().objectType() == RiskObjectType.SECTOR
                && snapshot.level() == RiskLevel.CRITICAL) {
            if (signal.originalConfidence().compareTo(SECTOR_BLOCK_CONFIDENCE) < 0) {
                action = RiskGateStatus.BLOCK;
                suggestedConfidence = BigDecimal.ZERO;
                reason = "板块 critical 且看涨置信度低于 0.70，建议阻断";
            } else {
                action = RiskGateStatus.DOWNGRADE;
                suggestedConfidence = reduce(signal.originalConfidence(), SECTOR_CRITICAL_REDUCTION);
                reason = "板块 critical，建议看涨置信度降低 0.20";
            }
        } else if (snapshot.object().objectType() == RiskObjectType.MARKET
                && snapshot.level().ordinal() >= RiskLevel.WARNING.ordinal()) {
            action = RiskGateStatus.DOWNGRADE;
            suggestedConfidence = reduce(signal.originalConfidence(), MARKET_WARNING_REDUCTION);
            reason = "市场 warning，建议看涨置信度降低 0.15";
        } else {
            action = RiskGateStatus.NORMAL;
            reason = "未触发影子风险闸门";
        }
        GateDecision decision = new GateDecision(
                snapshot.object(), signal.horizon(), signal.tradeDate(), signal.direction(),
                signal.originalConfidence(), suggestedConfidence, action, false, reason,
                snapshot.modelVersion(), calculatedAt);
        Map<String, Object> evidence = Map.of(
                "riskLevel", snapshot.level().getCode(),
                "riskScore", snapshot.totalScore(),
                "completeness", snapshot.completeness(),
                "riskConfidence", snapshot.riskConfidence());
        return new ShadowGateResult(signal.signalReference(), snapshot, decision, evidence);
    }

    private boolean completeCriticalStock(RiskSnapshot snapshot) {
        return snapshot.object().objectType() == RiskObjectType.STOCK
                && snapshot.level() == RiskLevel.CRITICAL
                && snapshot.vScore() != null
                && snapshot.tScore() != null
                && snapshot.sScore() != null
                && snapshot.cScore() != null
                && snapshot.aScore() != null;
    }

    private BigDecimal reduce(BigDecimal confidence, BigDecimal reduction) {
        return confidence.subtract(reduction).max(BigDecimal.ZERO);
    }

    private int actionRank(RiskGateStatus action) {
        return switch (action) {
            case NORMAL -> 0;
            case NOTICE -> 1;
            case DOWNGRADE -> 2;
            case BLOCK -> 3;
        };
    }

    private int objectRank(RiskObjectType objectType) {
        return switch (objectType) {
            case MARKET -> 0;
            case SECTOR -> 1;
            case STOCK -> 2;
        };
    }
}
