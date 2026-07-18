package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.model.RiskDataQualityStatus;
import com.jx.tracker.risk.model.RiskEvidence;
import com.jx.tracker.risk.model.RiskHorizon;
import com.jx.tracker.risk.model.RiskObjectKey;
import com.jx.tracker.risk.model.RiskSnapshot;
import com.jx.tracker.risk.provider.RiskEvent;
import com.jx.tracker.risk.provider.RiskIngestionCheckpoint;
import com.jx.tracker.risk.provider.RiskObservation;
import com.jx.tracker.risk.provider.RiskProviderBatch;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RiskWorkflowRepository {

    Optional<RiskIngestionCheckpoint> findCheckpoint(String providerCode, String datasetCode, String scopeKey);

    void saveObservation(RiskObservation observation);

    void saveEvent(RiskEvent event);

    void saveCheckpoint(String providerCode, String datasetCode, String scopeKey,
                        RiskIngestionCheckpoint checkpoint, RiskProviderBatch batch);

    List<RiskObservation> findObservations(RiskWorkflowRequest request);

    List<RiskSnapshot> findSnapshotHistory(RiskObjectKey object, RiskHorizon horizon,
                                           LocalDate startDate, LocalDate endDate,
                                           LocalDateTime asOf, String modelVersion);

    StoredRiskSnapshot saveSnapshot(RiskSnapshot snapshot, RiskDataQualityStatus qualityStatus,
                                    LocalDateTime observedAt, LocalDateTime availableAt);

    void saveEvidence(long snapshotId, RiskEvidence evidence);

    void saveGate(StoredRiskSnapshot snapshot, ShadowGateResult result,
                  LocalDateTime observedAt, LocalDateTime availableAt);
}
