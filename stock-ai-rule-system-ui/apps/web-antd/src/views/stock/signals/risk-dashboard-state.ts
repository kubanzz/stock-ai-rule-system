import type {
  RiskHorizon,
  RiskSnapshot,
  SignalDirection,
} from '#/api/stock/risk';

export type RiskSnapshotState =
  | 'empty'
  | 'insufficient'
  | 'ready'
  | 'stale'
  | 'unavailable';

export const RISK_HORIZON_OPTIONS: Array<{
  label: string;
  value: RiskHorizon;
}> = [
  { label: '短期（1-5日）', value: '1-5d' },
  { label: '中期（5-20日）', value: '5-20d' },
  { label: '长期（20-60日）', value: '20-60d' },
];

export const RISK_STAGE_LABELS = {
  easing: '缓和',
  fragile: '脆弱累积',
  repricing: '风险重定价',
  stampede: '踩踏确认',
} as const;

export const RISK_SNAPSHOT_STATE_LABELS: Record<RiskSnapshotState, string> = {
  empty: '暂无风险快照',
  insufficient: '风险数据不足',
  ready: '风险数据有效',
  stale: '风险数据已过期',
  unavailable: '风险数据不可用',
};

export function getRiskSnapshotState(
  snapshot: null | RiskSnapshot | undefined,
): RiskSnapshotState {
  if (!snapshot) return 'empty';
  if (snapshot.evidence.some((item) => item.qualityStatus === 'unavailable')) {
    return 'unavailable';
  }
  if (snapshot.evidence.some((item) => item.qualityStatus === 'stale')) {
    return 'stale';
  }
  if (
    snapshot.completeness < 0.8 ||
    snapshot.level === null ||
    snapshot.totalScore === null
  ) {
    return 'insufficient';
  }
  return 'ready';
}

export function isExplanationOnlyDirection(direction: SignalDirection) {
  return direction === 'bearish' || direction === 'watch';
}

export function formatConfidence(confidence: null | number | undefined) {
  if (confidence === null || confidence === undefined) return '--';
  return `${Math.round((confidence <= 1 ? confidence : confidence / 100) * 100)}%`;
}
