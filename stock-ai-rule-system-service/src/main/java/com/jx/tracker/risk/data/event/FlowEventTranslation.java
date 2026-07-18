package com.jx.tracker.risk.data.event;

import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskObservation;

import java.util.List;

public record FlowEventTranslation(
        List<RiskObservation> observations,
        List<RiskEvent> events,
        String rejectionReason
) {
    public FlowEventTranslation {
        observations = observations == null ? List.of() : List.copyOf(observations);
        events = events == null ? List.of() : List.copyOf(events);
    }

    public static FlowEventTranslation rejected(String reason) {
        return new FlowEventTranslation(List.of(), List.of(), reason);
    }
}
