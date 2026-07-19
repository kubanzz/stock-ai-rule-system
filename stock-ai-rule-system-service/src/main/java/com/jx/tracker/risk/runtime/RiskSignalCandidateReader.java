package com.jx.tracker.risk.runtime;

import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@FunctionalInterface
public interface RiskSignalCandidateReader {

    List<RiskSignalCandidate> read(
            LocalDate tradeDate,
            List<RiskObjectKey> stockObjects,
            List<RiskHorizon> horizons,
            LocalDateTime asOf
    );
}
