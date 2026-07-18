package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskObjectType;
import com.jx.tracker.risk.provider.RiskObservation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskNormalizerTest {

    private static final RiskObjectKey MARKET = new RiskObjectKey(RiskObjectType.MARKET, "CN-A");
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 7, 18, 16, 0);
    private final RiskNormalizer normalizer = new RiskNormalizer();

    @Test
    void calculatesRollingPercentileWithoutFutureInformation() {
        List<RiskObservation> history = observations(1, 2, 3, 4, 5);
        history.add(observation(new BigDecimal("999"), AS_OF.plusMinutes(1), 99));

        RiskNormalizationResult result = normalizer.rollingPercentile(
                new BigDecimal("4"), history, RiskHorizon.SHORT_TERM, AS_OF
        );

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.AVAILABLE);
        assertThat(result.sampleCount()).isEqualTo(5);
        assertThat(result.value()).isEqualByComparingTo("80.0000");
    }

    @Test
    void calculatesMedianMadRobustZAndVolatilityAdjustedValue() {
        List<RiskObservation> history = observations(1, 2, 3, 4, 5);

        RiskNormalizationResult robustZ = normalizer.robustZ(
                new BigDecimal("5"), history, RiskHorizon.SHORT_TERM, AS_OF
        );
        RiskNormalizationResult volatilityAdjusted = normalizer.volatilityAdjusted(
                new BigDecimal("2"), observations(-2, -1, 0, 1, 2), RiskHorizon.SHORT_TERM, AS_OF
        );

        assertThat(robustZ.value()).isEqualByComparingTo("1.3490");
        assertThat(volatilityAdjusted.value()).isCloseTo(
                new BigDecimal("1.4142"),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.0001"))
        );
    }

    @Test
    void reportsInsufficientHistoryInsteadOfInventingAZeroForANewStock() {
        RiskNormalizationResult result = normalizer.rollingPercentile(
                new BigDecimal("4"), observations(1, 2, 3, 4), RiskHorizon.SHORT_TERM, AS_OF
        );

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.value()).isNull();
        assertThat(result.sampleCount()).isEqualTo(4);
    }

    @Test
    void usesAtMostFiveYearsOfEligibleHistory() {
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 0; index < 1300; index++) {
            history.add(observation(
                    BigDecimal.valueOf(index),
                    AS_OF.minusDays(1300L - index),
                    index,
                    RiskHorizon.LONG_TERM
            ));
        }

        RiskNormalizationResult result = normalizer.rollingPercentile(
                new BigDecimal("1299"), history, RiskHorizon.LONG_TERM, AS_OF
        );

        assertThat(result.sampleCount()).isEqualTo(1250);
        assertThat(result.value()).isEqualByComparingTo("100.0000");
    }

    @Test
    void isolatesTheRequestedHorizonAndDoesNotMixOtherHorizonHistory() {
        List<RiskObservation> history = observations(1, 2, 3, 4, 5);
        for (int index = 0; index < 60; index++) {
            history.add(observation(
                    new BigDecimal("999"),
                    AS_OF.minusMinutes(index + 1L),
                    index,
                    RiskHorizon.LONG_TERM
            ));
        }

        RiskNormalizationResult result = normalizer.rollingPercentile(
                new BigDecimal("4"), history, RiskHorizon.SHORT_TERM, AS_OF
        );

        assertThat(result.sampleCount()).isEqualTo(5);
        assertThat(result.value()).isEqualByComparingTo("80.0000");
    }

    @Test
    void limitsHistoryByDistinctTradingDayAndKeepsTheLatestObservationPerDay() {
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 0; index < 1300; index++) {
            LocalDate tradeDate = AS_OF.toLocalDate().minusDays(1300L - index);
            history.add(observationForDate(
                    BigDecimal.ZERO,
                    tradeDate,
                    tradeDate.atTime(10, 0),
                    RiskHorizon.LONG_TERM
            ));
            history.add(observationForDate(
                    BigDecimal.valueOf(index + 1L),
                    tradeDate,
                    tradeDate.atTime(15, 0),
                    RiskHorizon.LONG_TERM
            ));
        }

        RiskNormalizationResult result = normalizer.rollingPercentile(
                BigDecimal.ZERO, history, RiskHorizon.LONG_TERM, AS_OF
        );

        assertThat(result.sampleCount()).isEqualTo(1250);
        assertThat(result.value()).isEqualByComparingTo("0.0000");
    }

    @Test
    void selectsTheFiveYearWindowByTradeDateRatherThanLateAvailability() {
        List<RiskObservation> history = new ArrayList<>();
        for (int index = 1; index <= 1250; index++) {
            LocalDate tradeDate = AS_OF.toLocalDate().minusDays(index);
            history.add(observationForDate(
                    BigDecimal.ONE,
                    tradeDate,
                    tradeDate.atTime(15, 0),
                    RiskHorizon.LONG_TERM
            ));
        }
        history.add(observationForDate(
                BigDecimal.ZERO,
                AS_OF.toLocalDate().minusDays(2000),
                AS_OF.minusMinutes(1),
                RiskHorizon.LONG_TERM
        ));

        RiskNormalizationResult result = normalizer.rollingPercentile(
                BigDecimal.ZERO, history, RiskHorizon.LONG_TERM, AS_OF
        );

        assertThat(result.sampleCount()).isEqualTo(1250);
        assertThat(result.value()).isEqualByComparingTo("0.0000");
    }

    @Test
    void rejectsMixedObjectsIndicatorsOrDimensionsInsteadOfSilentlyCombiningSeries() {
        List<RiskObservation> history = observations(1, 2, 3, 4, 5);
        RiskObservation source = history.getFirst();
        history.add(new RiskObservation(
                new RiskObjectKey(RiskObjectType.STOCK, "600000.SH"),
                source.horizon(),
                source.tradeDate().plusDays(1),
                source.dimension(),
                source.indicatorCode(),
                source.value(),
                source.unit(),
                source.observedAt(),
                source.availableAt(),
                source.source(),
                source.qualityStatus(),
                source.attributes()
        ));

        assertThatThrownBy(() -> normalizer.rollingPercentile(
                new BigDecimal("4"), history, RiskHorizon.SHORT_TERM, AS_OF
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single object/dimension/indicator series");
    }

    @Test
    void doesNotFallBackToAnOlderAvailableVersionWhenTheLatestDailyVersionIsUnavailable() {
        List<RiskObservation> history = observations(1, 2, 3, 4);
        LocalDate tradeDate = AS_OF.toLocalDate();
        history.add(observationForDate(
                new BigDecimal("80"),
                tradeDate,
                AS_OF.minusHours(2),
                RiskHorizon.SHORT_TERM
        ));
        history.add(new RiskObservation(
                MARKET,
                RiskHorizon.SHORT_TERM,
                tradeDate,
                RiskDimension.STRUCTURAL_FRAGILITY,
                "V3",
                null,
                "ratio",
                AS_OF.minusHours(1),
                AS_OF.minusMinutes(30),
                "test",
                RiskDataQualityStatus.UNAVAILABLE,
                Map.of()
        ));

        RiskNormalizationResult result = normalizer.rollingPercentile(
                new BigDecimal("80"), history, RiskHorizon.SHORT_TERM, AS_OF
        );

        assertThat(result.qualityStatus()).isEqualTo(RiskDataQualityStatus.INSUFFICIENT_HISTORY);
        assertThat(result.sampleCount()).isEqualTo(4);
        assertThat(result.value()).isNull();
    }

    private List<RiskObservation> observations(int... values) {
        List<RiskObservation> observations = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            observations.add(observation(
                    BigDecimal.valueOf(values[index]),
                    AS_OF.minusDays(values.length - index),
                    index,
                    RiskHorizon.SHORT_TERM
            ));
        }
        return observations;
    }

    private RiskObservation observation(BigDecimal value, LocalDateTime availableAt, int sequence) {
        return observation(value, availableAt, sequence, RiskHorizon.SHORT_TERM);
    }

    private RiskObservation observation(
            BigDecimal value,
            LocalDateTime availableAt,
            int sequence,
            RiskHorizon horizon
    ) {
        LocalDate tradeDate = LocalDate.of(2026, 7, 18).minusDays(Math.max(0, 1300L - sequence));
        return observationForDate(value, tradeDate, availableAt, horizon);
    }

    private RiskObservation observationForDate(
            BigDecimal value,
            LocalDate tradeDate,
            LocalDateTime availableAt,
            RiskHorizon horizon
    ) {
        return new RiskObservation(
                MARKET,
                horizon,
                tradeDate,
                RiskDimension.STRUCTURAL_FRAGILITY,
                "V3",
                value,
                "ratio",
                availableAt.minusMinutes(1),
                availableAt,
                "test",
                RiskDataQualityStatus.AVAILABLE,
                Map.of()
        );
    }
}
