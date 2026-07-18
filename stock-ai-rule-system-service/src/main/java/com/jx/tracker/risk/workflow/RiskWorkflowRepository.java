package com.jx.tracker.risk.workflow;

import com.jx.tracker.risk.gate.ShadowGateResult;
import com.jx.tracker.risk.gate.RiskSignalCandidate;
import com.jx.tracker.risk.data.market.IndustryExposure;
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

    /** 记录采集状态但保留已有逻辑游标；用于 unavailable 等失败批次。 */
    void saveIngestionStatus(String providerCode, String datasetCode, String scopeKey,
                             RiskIngestionCheckpoint currentCheckpoint, RiskProviderBatch batch);

    List<RiskObservation> findObservations(RiskWorkflowRequest request);

    List<RiskEvent> findEvents(RiskWorkflowRequest request);

    List<IndustryExposure> findIndustryExposures(RiskWorkflowRequest request);

    /** 单次批量加载本轮所需历史，调用方在内存按对象、周期和日期索引。 */
    List<RiskSnapshot> findSnapshotHistory(RiskWorkflowRequest request);

    StoredRiskSnapshot saveSnapshot(RiskSnapshot snapshot, RiskDataQualityStatus qualityStatus,
                                    LocalDateTime observedAt, LocalDateTime availableAt);

    void replaceEvidence(long snapshotId, RiskObjectKey snapshotObject, List<RiskEvidence> evidence);

    void deleteGate(RiskSignalCandidate signal, String modelVersion);

    void saveGate(StoredRiskSnapshot snapshot, ShadowGateResult result,
                  LocalDateTime observedAt, LocalDateTime availableAt);
}
