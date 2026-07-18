package com.jx.tracker.risk.gate;

import com.jx.tracker.risk.model.RiskGateStatus;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskLevel;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.model.RiskStage;
import com.jx.tracker.risk.model.SignalDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowRiskGateTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 18);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 18, 18, 0);
    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final RiskObjectKey SECTOR = new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801120");
    private static final RiskObjectKey STOCK = new RiskObjectKey(RiskObjectType.STOCK, "600519.SH");
    private final ShadowRiskGate gate = new ShadowRiskGate();

    @Test
    void marketWarningSuggestsFifteenPointConfidenceDowngrade() {
        ShadowGateResult result = gate.evaluate(candidate(SignalDirection.BULLISH, "0.80"),
                List.of(snapshot(MARKET, RiskLevel.WARNING, "0.90")), NOW).orElseThrow();

        assertThat(result.decision().suggestedAction()).isEqualTo(RiskGateStatus.DOWNGRADE);
        assertThat(result.decision().originalConfidence()).isEqualByComparingTo("0.80");
        assertThat(result.decision().suggestedConfidence()).isEqualByComparingTo("0.65");
        assertThat(result.decision().enforced()).isFalse();
    }

    @Test
    void sectorCriticalBlocksLowConfidenceBullishAndDowngradesOthers() {
        ShadowGateResult blocked = gate.evaluate(candidate(SignalDirection.BULLISH, "0.69"),
                List.of(snapshot(SECTOR, RiskLevel.CRITICAL, "0.90")), NOW).orElseThrow();
        ShadowGateResult downgraded = gate.evaluate(candidate(SignalDirection.BULLISH, "0.80"),
                List.of(snapshot(SECTOR, RiskLevel.CRITICAL, "0.90")), NOW).orElseThrow();

        assertThat(blocked.decision().suggestedAction()).isEqualTo(RiskGateStatus.BLOCK);
        assertThat(blocked.decision().suggestedConfidence()).isEqualByComparingTo("0.00");
        assertThat(downgraded.decision().suggestedAction()).isEqualTo(RiskGateStatus.DOWNGRADE);
        assertThat(downgraded.decision().suggestedConfidence()).isEqualByComparingTo("0.60");
    }

    @Test
    void completeCriticalStockSnapshotBlocksBullish() {
        ShadowGateResult result = gate.evaluate(candidate(SignalDirection.BULLISH, "0.88"),
                List.of(snapshot(STOCK, RiskLevel.CRITICAL, "0.80")), NOW).orElseThrow();

        assertThat(result.snapshot().object()).isEqualTo(STOCK);
        assertThat(result.decision().suggestedAction()).isEqualTo(RiskGateStatus.BLOCK);
        assertThat(result.decision().suggestedConfidence()).isEqualByComparingTo("0.00");
    }

    @Test
    void bearishAndWatchOnlyReceiveNoticeWithoutDirectionOrConfidenceRewrite() {
        ShadowGateResult bearish = gate.evaluate(candidate(SignalDirection.BEARISH, "0.73"),
                List.of(snapshot(STOCK, RiskLevel.CRITICAL, "0.90")), NOW).orElseThrow();
        ShadowGateResult watch = gate.evaluate(candidate(SignalDirection.WATCH, "0.44"),
                List.of(snapshot(SECTOR, RiskLevel.CRITICAL, "0.90")), NOW).orElseThrow();

        assertThat(bearish.decision().signalDirection()).isEqualTo(SignalDirection.BEARISH);
        assertThat(bearish.decision().suggestedAction()).isEqualTo(RiskGateStatus.NOTICE);
        assertThat(bearish.decision().suggestedConfidence()).isEqualByComparingTo("0.73");
        assertThat(watch.decision().signalDirection()).isEqualTo(SignalDirection.WATCH);
        assertThat(watch.decision().suggestedAction()).isEqualTo(RiskGateStatus.NOTICE);
        assertThat(watch.decision().suggestedConfidence()).isEqualByComparingTo("0.44");
    }

    @Test
    void incompleteSnapshotDoesNotProduceGateAndStrongestRelevantSnapshotWins() {
        RiskSnapshot incomplete = new RiskSnapshot(
                STOCK, RiskHorizon.SHORT_TERM, DATE,
                null, null, null, null, null, new BigDecimal("1.00"),
                null, null, null, new BigDecimal("0.50"), null,
                List.of(), "risk-v1", NOW);

        assertThat(gate.evaluate(candidate(SignalDirection.BULLISH, "0.80"),
                List.of(incomplete), NOW)).isEmpty();

        ShadowGateResult strongest = gate.evaluate(candidate(SignalDirection.BULLISH, "0.80"), List.of(
                snapshot(MARKET, RiskLevel.WARNING, "0.90"),
                snapshot(STOCK, RiskLevel.CRITICAL, "0.90")
        ), NOW).orElseThrow();
        assertThat(strongest.snapshot().object()).isEqualTo(STOCK);
        assertThat(strongest.decision().suggestedAction()).isEqualTo(RiskGateStatus.BLOCK);
    }

    private RiskSignalCandidate candidate(SignalDirection direction, String confidence) {
        return new RiskSignalCandidate(
                "signal:600519.SH:2026-07-18", STOCK, RiskHorizon.SHORT_TERM, DATE,
                direction, new BigDecimal(confidence), List.of(MARKET, SECTOR, STOCK));
    }

    private RiskSnapshot snapshot(RiskObjectKey object, RiskLevel level, String completeness) {
        return new RiskSnapshot(
                object, RiskHorizon.SHORT_TERM, DATE,
                new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("80"),
                new BigDecimal("80"), new BigDecimal("80"), new BigDecimal("1.00"),
                new BigDecimal("80"), level, RiskStage.STAMPEDE,
                new BigDecimal(completeness), new BigDecimal(completeness),
                List.of(), "risk-v1", NOW);
    }
}
