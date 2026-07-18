package com.jx.tracker.risk.model;

import java.util.HashMap;
import java.util.Map;

/** 保留合成证据所属市场、行业或个股层，避免同指标同来源在持久化时互相覆盖。 */
public final class RiskEvidenceProvenance {

    public static final String LAYER_OBJECT_TYPE = "layerObjectType";
    public static final String LAYER_OBJECT_ID = "layerObjectId";

    private RiskEvidenceProvenance() {
    }

    public static RiskEvidence withLayer(RiskEvidence evidence, RiskObjectKey layerObject) {
        if (evidence == null || layerObject == null) {
            throw new IllegalArgumentException("evidence and layerObject are required");
        }
        Map<String, Object> details = new HashMap<>(evidence.details());
        details.put(LAYER_OBJECT_TYPE, layerObject.objectType().getCode());
        details.put(LAYER_OBJECT_ID, layerObject.objectId());
        return new RiskEvidence(
                evidence.dimension(), evidence.indicatorCode(), evidence.score(), evidence.rawValue(),
                evidence.observedAt(), evidence.availableAt(), evidence.source(), evidence.qualityStatus(), details);
    }

    public static RiskObjectKey layerObject(RiskEvidence evidence, RiskObjectKey fallback) {
        Object type = evidence.details().get(LAYER_OBJECT_TYPE);
        Object id = evidence.details().get(LAYER_OBJECT_ID);
        if (type == null || id == null) {
            if (fallback == null) {
                throw new IllegalArgumentException("evidence layer provenance is required");
            }
            return fallback;
        }
        return new RiskObjectKey(
                RiskObjectType.fromCode(String.valueOf(type)), String.valueOf(id));
    }
}
