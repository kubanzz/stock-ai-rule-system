package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.engine.RiskNormalizationResult;
import com.jx.tracker.risk.engine.RiskNormalizer;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskObservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 默认以 5 年内、各周期最大上下文窗口的滚动分位生成 0-100 风险证据。 */
public final class PercentileRiskEvidenceAssembler implements RiskEvidenceAssembler {

    private final RiskNormalizer normalizer;

    public PercentileRiskEvidenceAssembler(RiskNormalizer normalizer) {
        if (normalizer == null) {
            throw new IllegalArgumentException("normalizer must not be null");
        }
        this.normalizer = normalizer;
    }

    @Override
    public List<RiskEvidence> assemble(
            RiskObjectKey object,
            RiskHorizon horizon,
            LocalDate tradeDate,
            LocalDateTime asOf,
            List<RiskObservation> observations
    ) {
        Map<String, RiskObservation> currentByIndicator = new LinkedHashMap<>();
        Map<String, List<RiskObservation>> historyByIndicator = observations.stream()
                .filter(observation -> observation.object().equals(object))
                .filter(observation -> observation.horizon() == horizon)
                .filter(observation -> !observation.tradeDate().isAfter(tradeDate))
                .filter(observation -> !observation.availableAt().isAfter(asOf))
                .collect(java.util.stream.Collectors.groupingBy(
                        observation -> indicatorKey(observation.dimension().getCode(), observation.indicatorCode()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        historyByIndicator.values().stream()
                .flatMap(List::stream)
                .filter(observation -> observation.tradeDate().equals(tradeDate))
                .sorted(Comparator.comparing(RiskObservation::availableAt))
                .forEach(observation -> currentByIndicator.put(
                        indicatorKey(observation.dimension().getCode(), observation.indicatorCode()), observation));
        return currentByIndicator.values().stream()
                .sorted(Comparator.comparing(RiskObservation::indicatorCode))
                .map(current -> evidence(
                        current,
                        historyByIndicator.getOrDefault(
                                indicatorKey(current.dimension().getCode(), current.indicatorCode()), List.of()),
                        horizon, asOf))
                .toList();
    }

    private RiskEvidence evidence(
            RiskObservation current,
            List<RiskObservation> series,
            RiskHorizon horizon,
            LocalDateTime asOf
    ) {
        if (current.qualityStatus() != RiskDataQualityStatus.AVAILABLE
                && current.qualityStatus() != RiskDataQualityStatus.VALID_ZERO) {
            return new RiskEvidence(
                    current.dimension(), current.indicatorCode(), null, null,
                    current.observedAt(), current.availableAt(), current.source(),
                    current.qualityStatus(), Map.of("normalization", "rolling_percentile"));
        }
        RiskNormalizationResult normalized = normalizer.rollingPercentile(
                current.value(), series, horizon, asOf);
        if (normalized.qualityStatus() == RiskDataQualityStatus.INSUFFICIENT_HISTORY) {
            return new RiskEvidence(
                    current.dimension(), current.indicatorCode(), null, null,
                    current.observedAt(), current.availableAt(), current.source(),
                    RiskDataQualityStatus.INSUFFICIENT_HISTORY,
                    Map.of("normalization", "rolling_percentile", "sampleCount", normalized.sampleCount()));
        }
        return new RiskEvidence(
                current.dimension(), current.indicatorCode(), normalized.value(), current.value(),
                current.observedAt(), current.availableAt(), current.source(),
                RiskDataQualityStatus.AVAILABLE,
                Map.of("normalization", "rolling_percentile", "sampleCount", normalized.sampleCount()));
    }

    private String indicatorKey(String dimensionCode, String indicatorCode) {
        return dimensionCode + ":" + indicatorCode;
    }
}
