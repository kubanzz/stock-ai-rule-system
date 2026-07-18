package com.jx.tracker.risk.provider;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskDataProviderContractTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 7, 18, 15, 0);
    private static final LocalDateTime AVAILABLE_AT = LocalDateTime.of(2026, 7, 18, 16, 0);

    @Test
    void distinguishesAValidEmptyEventBatchFromProviderFailure() {
        RiskProviderBatch validZero = RiskProviderBatch.validZero(
                "aktools",
                new RiskIngestionCheckpoint("announcements", "CN-A", "cursor-18", AVAILABLE_AT)
        );
        RiskProviderBatch failed = RiskProviderBatch.unavailable("aktools", "source timeout", AVAILABLE_AT);

        assertThat(validZero.qualityStatus()).isEqualTo(RiskDataQualityStatus.VALID_ZERO);
        assertThat(validZero.observations()).isEmpty();
        assertThat(validZero.events()).isEmpty();
        assertThat(validZero.errorMessage()).isNull();

        assertThat(failed.qualityStatus()).isEqualTo(RiskDataQualityStatus.UNAVAILABLE);
        assertThat(failed.errorMessage()).isEqualTo("source timeout");
    }

    @Test
    void observationRequiresAvailabilityMetadataAndDoesNotConflateMissingWithZero() {
        RiskObservation observation = new RiskObservation(
                MARKET,
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                RiskDimension.VALUATION,
                "market_pe_percentile",
                BigDecimal.ZERO,
                "ratio",
                OBSERVED_AT,
                AVAILABLE_AT,
                "aktools",
                RiskDataQualityStatus.AVAILABLE,
                Map.of()
        );

        assertThat(observation.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThatThrownBy(() -> new RiskObservation(
                MARKET, RiskHorizon.SHORT_TERM, LocalDate.of(2026, 7, 18), RiskDimension.VALUATION,
                "market_pe_percentile", null, "ratio", OBSERVED_AT, AVAILABLE_AT,
                "aktools", RiskDataQualityStatus.AVAILABLE, Map.of()
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("value");
    }

    @Test
    void observationEnforcesValueSemanticsForEveryQualityStatus() {
        assertThatThrownBy(() -> observation(BigDecimal.ONE, RiskDataQualityStatus.VALID_ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid_zero")
                .hasMessageContaining("zero");

        assertThatThrownBy(() -> observation(BigDecimal.ZERO, RiskDataQualityStatus.UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unavailable")
                .hasMessageContaining("null");
        assertThatThrownBy(() -> observation(BigDecimal.ZERO, RiskDataQualityStatus.STALE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stale")
                .hasMessageContaining("null");
        assertThatThrownBy(() -> observation(BigDecimal.ZERO, RiskDataQualityStatus.INSUFFICIENT_HISTORY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("insufficient_history")
                .hasMessageContaining("null");

        assertThat(observation(BigDecimal.ZERO, RiskDataQualityStatus.VALID_ZERO).value())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(observation(null, RiskDataQualityStatus.UNAVAILABLE).value()).isNull();
    }

    @Test
    void directBatchConstructionRejectsContradictoryRecordsAndErrors() {
        RiskObservation observation = observation(BigDecimal.ONE, RiskDataQualityStatus.AVAILABLE);

        assertThatThrownBy(() -> new RiskProviderBatch(
                "aktools", List.of(observation), List.of(), null,
                RiskDataQualityStatus.UNAVAILABLE, "source timeout", AVAILABLE_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unavailable");

        assertThatThrownBy(() -> new RiskProviderBatch(
                "aktools", List.of(), List.of(), null,
                RiskDataQualityStatus.AVAILABLE, "unexpected error", AVAILABLE_AT
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("available");
    }

    @Test
    void requestSupportsBatchObjectsAndResumeCheckpoint() {
        RiskIngestionCheckpoint checkpoint = new RiskIngestionCheckpoint(
                "valuation", "CN-A", "2026-07-17", OBSERVED_AT
        );
        RiskProviderRequest request = new RiskProviderRequest(
                List.of(MARKET, new RiskObjectKey(RiskObjectType.SECTOR, "SW1:801010")),
                List.of(RiskHorizon.SHORT_TERM, RiskHorizon.MEDIUM_TERM),
                LocalDate.of(2021, 7, 18),
                LocalDate.of(2026, 7, 18),
                checkpoint
        );

        assertThat(request.objects()).hasSize(2);
        assertThat(request.checkpoint()).isEqualTo(checkpoint);
    }

    private RiskObservation observation(BigDecimal value, RiskDataQualityStatus status) {
        return new RiskObservation(
                MARKET,
                RiskHorizon.SHORT_TERM,
                LocalDate.of(2026, 7, 18),
                RiskDimension.VALUATION,
                "market_pe_percentile",
                value,
                "ratio",
                OBSERVED_AT,
                AVAILABLE_AT,
                "aktools",
                status,
                Map.of()
        );
    }
}
