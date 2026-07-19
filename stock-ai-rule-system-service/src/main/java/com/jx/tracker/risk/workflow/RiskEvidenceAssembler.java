package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.provider.RiskObservation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@FunctionalInterface
public interface RiskEvidenceAssembler {

    List<RiskEvidence> assemble(RiskObjectKey object, RiskHorizon horizon, LocalDate tradeDate,
                                LocalDateTime asOf, List<RiskObservation> observations);
}
