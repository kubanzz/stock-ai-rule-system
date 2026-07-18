package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskDimension;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskObservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RiskNormalizer {

    private static final int SCALE = 4;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal ROBUST_Z_FACTOR = new BigDecimal("0.6745");

    public RiskNormalizationResult rollingPercentile(
            BigDecimal currentValue,
            List<RiskObservation> history,
            RiskHorizon horizon,
            LocalDateTime asOf
    ) {
        List<BigDecimal> values = eligibleValues(history, horizon, asOf);
        RiskNormalizationResult insufficient = insufficientIfNeeded(values, horizon);
        if (insufficient != null) {
            return insufficient;
        }
        long notGreater = values.stream().filter(value -> value.compareTo(currentValue) <= 0).count();
        BigDecimal percentile = BigDecimal.valueOf(notGreater)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
        return available(percentile, values.size());
    }

    public RiskNormalizationResult robustZ(
            BigDecimal currentValue,
            List<RiskObservation> history,
            RiskHorizon horizon,
            LocalDateTime asOf
    ) {
        List<BigDecimal> values = eligibleValues(history, horizon, asOf).stream().sorted().toList();
        RiskNormalizationResult insufficient = insufficientIfNeeded(values, horizon);
        if (insufficient != null) {
            return insufficient;
        }
        BigDecimal median = median(values);
        List<BigDecimal> absoluteDeviations = values.stream()
                .map(value -> value.subtract(median).abs())
                .sorted()
                .toList();
        BigDecimal mad = median(absoluteDeviations);
        if (mad.signum() == 0) {
            if (currentValue.compareTo(median) == 0) {
                return available(BigDecimal.ZERO.setScale(SCALE), values.size());
            }
            return insufficient(values.size());
        }
        BigDecimal robustZ = currentValue.subtract(median)
                .multiply(ROBUST_Z_FACTOR)
                .divide(mad, SCALE, RoundingMode.HALF_UP);
        return available(robustZ, values.size());
    }

    public RiskNormalizationResult volatilityAdjusted(
            BigDecimal currentValue,
            List<RiskObservation> history,
            RiskHorizon horizon,
            LocalDateTime asOf
    ) {
        List<BigDecimal> values = eligibleValues(history, horizon, asOf);
        RiskNormalizationResult insufficient = insufficientIfNeeded(values, horizon);
        if (insufficient != null) {
            return insufficient;
        }
        double mean = values.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        double variance = values.stream()
                .mapToDouble(value -> {
                    double delta = value.doubleValue() - mean;
                    return delta * delta;
                })
                .average()
                .orElse(0.0);
        double volatility = Math.sqrt(variance);
        if (volatility == 0.0) {
            return insufficient(values.size());
        }
        BigDecimal adjusted = BigDecimal.valueOf(currentValue.doubleValue() / volatility)
                .setScale(SCALE, RoundingMode.HALF_UP);
        return available(adjusted, values.size());
    }

    private List<BigDecimal> eligibleValues(
            List<RiskObservation> history,
            RiskHorizon horizon,
            LocalDateTime asOf
    ) {
        if (history == null || horizon == null || asOf == null) {
            throw new IllegalArgumentException("history, horizon and asOf must not be null");
        }
        int maximum = RiskHorizonProfile.forHorizon(horizon).maxHistoryDays();
        Map<java.time.LocalDate, RiskObservation> latestByTradingDay = new HashMap<>();
        ObservationSeriesKey seriesKey = null;
        for (RiskObservation observation : history) {
            if (observation.horizon() != horizon
                    || observation.availableAt().isAfter(asOf)) {
                continue;
            }
            ObservationSeriesKey observationSeriesKey = new ObservationSeriesKey(
                    observation.object(), observation.dimension(), observation.indicatorCode()
            );
            if (seriesKey == null) {
                seriesKey = observationSeriesKey;
            } else if (!seriesKey.equals(observationSeriesKey)) {
                throw new IllegalArgumentException(
                        "history must contain a single object/dimension/indicator series"
                );
            }
            latestByTradingDay.merge(
                    observation.tradeDate(),
                    observation,
                    (left, right) -> left.availableAt().isBefore(right.availableAt()) ? right : left
            );
        }
        return latestByTradingDay.values().stream()
                .sorted(Comparator.comparing(RiskObservation::tradeDate).reversed())
                .limit(maximum)
                .filter(observation -> observation.qualityStatus() == RiskDataQualityStatus.AVAILABLE
                        || observation.qualityStatus() == RiskDataQualityStatus.VALID_ZERO)
                .filter(observation -> observation.value() != null)
                .map(RiskObservation::value)
                .toList();
    }

    private RiskNormalizationResult insufficientIfNeeded(List<BigDecimal> values, RiskHorizon horizon) {
        if (values.size() < RiskHorizonProfile.forHorizon(horizon).primaryWindow()) {
            return insufficient(values.size());
        }
        return null;
    }

    private BigDecimal median(List<BigDecimal> sortedValues) {
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) {
            return sortedValues.get(middle);
        }
        return sortedValues.get(middle - 1)
                .add(sortedValues.get(middle))
                .divide(BigDecimal.valueOf(2), SCALE + 4, RoundingMode.HALF_UP);
    }

    private RiskNormalizationResult available(BigDecimal value, int sampleCount) {
        return new RiskNormalizationResult(value, RiskDataQualityStatus.AVAILABLE, sampleCount);
    }

    private RiskNormalizationResult insufficient(int sampleCount) {
        return new RiskNormalizationResult(null, RiskDataQualityStatus.INSUFFICIENT_HISTORY, sampleCount);
    }

    private record ObservationSeriesKey(
            RiskObjectKey object,
            RiskDimension dimension,
            String indicatorCode
    ) {
    }
}
