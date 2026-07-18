package com.jx.tracker.risk.engine;

import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 固定层级权重合成后的股票风险评分请求。 */
public record RiskLayerScoreRequest(
        RiskObjectKey object,
        RiskHorizon horizon,
        LocalDate tradeDate,
        LocalDate previousTradingDate,
        LocalDateTime asOf,
        RiskLayerComposition composition,
        List<RiskSnapshot> history,
        ExtremeRiskConfirmation extremeConfirmation,
        String modelVersion
) {
    public RiskLayerScoreRequest {
        if (object == null || horizon == null || tradeDate == null || asOf == null
                || composition == null || extremeConfirmation == null) {
            throw new IllegalArgumentException("layer score request fields are required");
        }
        history = history == null ? List.of() : List.copyOf(history);
        if (modelVersion == null || modelVersion.isBlank()) {
            throw new IllegalArgumentException("modelVersion must not be blank");
        }
    }
}
