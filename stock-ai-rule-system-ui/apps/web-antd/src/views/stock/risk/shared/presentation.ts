import type {
  RiskEvidence,
  RiskGateStatus,
  RiskLevel,
} from '#/api/stock/risk/types';

export interface RiskPresentation {
  color: string;
  label: string;
}

const levelPresentation: Record<RiskLevel, RiskPresentation> = {
  critical: { color: 'red', label: '严重' },
  normal: { color: 'green', label: '正常' },
  warning: { color: 'orange', label: '预警' },
  watch: { color: 'gold', label: '关注' },
};

const gatePresentation: Record<RiskGateStatus, RiskPresentation> = {
  block: { color: 'red', label: '建议拦截' },
  downgrade: { color: 'orange', label: '建议降级' },
  normal: { color: 'green', label: '正常' },
  notice: { color: 'blue', label: '风险提示' },
};

export function getRiskLevelPresentation(
  level: null | RiskLevel | undefined,
): RiskPresentation {
  return level
    ? levelPresentation[level]
    : { color: 'default', label: '数据不足' };
}

export function getGatePresentation(
  status: RiskGateStatus,
  enforced: boolean,
): RiskPresentation {
  const presentation = gatePresentation[status];
  return enforced
    ? presentation
    : { ...presentation, label: `${presentation.label}（影子）` };
}

export function getRiskScoreTone(
  score: null | number | undefined,
): 'high' | 'low' | 'medium' | 'unavailable' {
  if (score === null || score === undefined) return 'unavailable';
  if (score >= 65) return 'high';
  if (score >= 40) return 'medium';
  return 'low';
}

export function formatRiskScore(score: null | number | undefined) {
  return score === null || score === undefined ? '--' : score.toFixed(1);
}

function evidenceDetail(
  evidence: RiskEvidence,
  key: 'componentCode' | 'layerObjectId' | 'layerObjectType',
) {
  const value = evidence.details[key];
  return typeof value === 'string' && value.length > 0 ? value : 'unknown';
}

export function riskEvidenceKey(evidence: RiskEvidence) {
  return [
    evidenceDetail(evidence, 'layerObjectType'),
    evidenceDetail(evidence, 'layerObjectId'),
    evidence.dimension,
    evidence.indicatorCode,
    evidenceDetail(evidence, 'componentCode'),
    evidence.source,
    evidence.observedAt,
    evidence.availableAt,
  ].join(':');
}
