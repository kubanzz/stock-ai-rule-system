package com.jx.tracker.risk.provider;

import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;
import java.util.List;

public record RiskProviderRequest(
        List<RiskObjectKey> objects,
        List<RiskHorizon> horizons,
        LocalDate startDate,
        LocalDate resultStartDate,
        LocalDate endDate,
        RiskIngestionCheckpoint checkpoint
) {

    public RiskProviderRequest {
        objects = objects == null ? List.of() : List.copyOf(objects);
        horizons = horizons == null ? List.of() : List.copyOf(horizons);
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("objects must not be empty");
        }
        if (horizons.isEmpty()) {
            throw new IllegalArgumentException("horizons must not be empty");
        }
        if (startDate == null || resultStartDate == null || endDate == null) {
            throw new IllegalArgumentException(
                    "startDate, resultStartDate and endDate must not be null");
        }
        if (resultStartDate.isBefore(startDate) || endDate.isBefore(resultStartDate)) {
            throw new IllegalArgumentException(
                    "dates must satisfy startDate <= resultStartDate <= endDate");
        }
    }

    public RiskProviderRequest(
            List<RiskObjectKey> objects,
            List<RiskHorizon> horizons,
            LocalDate startDate,
            LocalDate endDate,
            RiskIngestionCheckpoint checkpoint
    ) {
        this(objects, horizons, startDate, startDate, endDate, checkpoint);
    }
}
