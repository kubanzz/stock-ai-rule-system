import type {
  RiskEvidence,
  RiskObjectDetail,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskOverview,
  RiskOverviewQuery,
  RiskSnapshot,
  RiskTrendQuery,
  RiskTrendPoint,
} from './types';

import { RISK_DECISION_SUPPORT_NOTICE } from './types';

const calculatedAt = '2026-07-18T18:10:00+08:00';

function evidence(
  dimension: RiskEvidence['dimension'],
  indicatorCode: string,
  score: number,
  rawValue: number,
  source = 'aktools',
): RiskEvidence {
  return {
    availableAt: '2026-07-18T16:30:00+08:00',
    details: { baselineYears: 5, percentile: score / 100 },
    dimension,
    indicatorCode,
    observedAt: '2026-07-18T15:00:00+08:00',
    qualityStatus: rawValue === 0 ? 'valid_zero' : 'available',
    rawValue,
    score,
    source,
  };
}

function snapshot(
  overrides: Partial<RiskSnapshot> &
    Pick<RiskSnapshot, 'horizon' | 'object'>,
): RiskSnapshot {
  const { horizon, object, ...rest } = overrides;
  return {
    aScore: 48,
    cScore: 63,
    calculatedAt,
    completeness: 0.92,
    evidence: [
      evidence('V', 'V1', 74, 18.6),
      evidence('C', 'C2', 66, 0.31),
      evidence('A', 'A2', 51, -12.4),
    ],
    horizon,
    level: 'warning',
    mScore: 1.05,
    modelVersion: 'risk-v1.0-shadow',
    object,
    riskConfidence: 0.86,
    sScore: 58,
    stage: 'repricing',
    tScore: 55,
    totalScore: 62.4,
    tradeDate: '2026-07-18',
    vScore: 72,
    ...rest,
  };
}

export const mockRiskSnapshots: RiskSnapshot[] = [
  snapshot({
    horizon: '1-5d',
    object: { objectId: 'CN-A', objectType: 'market' },
  }),
  snapshot({
    aScore: 39,
    cScore: 51,
    horizon: '5-20d',
    level: 'watch',
    mScore: 1,
    object: { objectId: 'CN-A', objectType: 'market' },
    stage: 'fragile',
    totalScore: 57.2,
  }),
  snapshot({
    aScore: 31,
    cScore: 44,
    horizon: '20-60d',
    level: 'watch',
    mScore: 0.95,
    object: { objectId: 'CN-A', objectType: 'market' },
    stage: 'fragile',
    totalScore: 54.8,
  }),
  snapshot({
    horizon: '1-5d',
    level: 'warning',
    object: { objectId: 'SW1:801010', objectType: 'sector' },
    totalScore: 64.1,
  }),
  snapshot({
    aScore: 56,
    cScore: 70,
    horizon: '1-5d',
    level: 'critical',
    object: { objectId: '600519.SH', objectType: 'stock' },
    stage: 'stampede',
    totalScore: 69.5,
  }),
  snapshot({
    aScore: 47,
    cScore: 61,
    horizon: '5-20d',
    level: 'warning',
    object: { objectId: '600519.SH', objectType: 'stock' },
    totalScore: 63.8,
  }),
  snapshot({
    aScore: 36,
    cScore: 55,
    horizon: '20-60d',
    level: 'watch',
    object: { objectId: '600519.SH', objectType: 'stock' },
    stage: 'fragile',
    totalScore: 59.6,
  }),
];

const incompleteSnapshot = snapshot({
  aScore: null,
  cScore: 42,
  completeness: 0.58,
  evidence: [
    {
      availableAt: '2026-07-18T16:30:00+08:00',
      details: { missingItems: ['A1', 'A2', 'A3', 'A4', 'A5'] },
      dimension: 'A',
      indicatorCode: 'A1',
      observedAt: '2026-07-18T15:00:00+08:00',
      qualityStatus: 'insufficient_history',
      rawValue: null,
      score: null,
      source: 'aktools',
    },
  ],
  horizon: '1-5d',
  level: null,
  mScore: 1,
  object: { objectId: '688999.SH', objectType: 'stock' },
  riskConfidence: null,
  stage: null,
  totalScore: null,
  vScore: null,
});

export const mockIncompleteRiskObject: RiskObjectListItem = {
  name: '历史不足示例',
  object: incompleteSnapshot.object,
  parentName: '申万一级行业待补齐',
  snapshot: incompleteSnapshot,
};

const marketSnapshot = mockRiskSnapshots[0]!;
const sectorSnapshot = mockRiskSnapshots[3]!;
const stockSnapshot = mockRiskSnapshots[4]!;

export const mockRiskObjects: RiskObjectListItem[] = [
  {
    name: 'A 股全市场',
    object: marketSnapshot.object,
    snapshot: marketSnapshot,
  },
  {
    name: '农林牧渔',
    object: sectorSnapshot.object,
    parentName: 'A 股全市场',
    snapshot: sectorSnapshot,
  },
  {
    gateDecision: {
      calculatedAt,
      enforced: false,
      horizon: '1-5d',
      modelVersion: 'risk-v1.0-shadow',
      object: stockSnapshot.object,
      originalConfidence: 0.78,
      reason: '个股完整红色门控，建议拦截看涨信号。',
      signalDirection: 'bullish',
      suggestedAction: 'block',
      suggestedConfidence: 0.58,
      tradeDate: '2026-07-18',
    },
    name: '贵州茅台',
    object: stockSnapshot.object,
    parentName: '食品饮料',
    snapshot: stockSnapshot,
  },
  mockIncompleteRiskObject,
];

export const mockRiskOverview: RiskOverview = {
  highRiskObjects: mockRiskObjects.filter(
    (item) => item.snapshot.level === 'critical',
  ),
  horizon: '1-5d',
  levelCounts: [
    { count: 18, level: 'normal' },
    { count: 7, level: 'watch' },
    { count: 3, level: 'warning' },
    { count: 1, level: 'critical' },
  ],
  marketSnapshot,
  riskDisclaimer: RISK_DECISION_SUPPORT_NOTICE,
  tradeDate: '2026-07-18',
};

export const mockRiskObjectDetail: RiskObjectDetail = {
  ...mockRiskObjects[2]!,
  activeTriggers: ['估值高分位', '市场宽度恶化', 'ETF 连续净流出'],
  parentObjects: [
    { objectId: 'CN-A', objectType: 'market' },
    { objectId: 'SW1:801120', objectType: 'sector' },
  ],
  snapshots: mockRiskSnapshots.filter(
    (item) => item.object.objectId === '600519.SH',
  ),
};

export const mockRiskTrend: RiskTrendPoint[] = [
  ['2026-07-14', 53.2, 'watch'],
  ['2026-07-15', 58.4, 'watch'],
  ['2026-07-16', 64.8, 'warning'],
  ['2026-07-17', 67.1, 'warning'],
  ['2026-07-18', 69.5, 'critical'],
].map(([tradeDate, totalScore, level], index) => ({
  aScore: 40 + index * 4,
  cScore: 54 + index * 4,
  completeness: 0.9,
  level: level as RiskTrendPoint['level'],
  sScore: 56,
  tScore: 55,
  totalScore: totalScore as number,
  tradeDate: tradeDate as string,
  vScore: 72,
}));

export function selectMockRiskOverview(
  query: RiskOverviewQuery = {},
): RiskOverview {
  const horizon = query.horizon ?? '1-5d';
  const tradeDate = query.tradeDate ?? mockRiskOverview.tradeDate;
  const snapshots = mockRiskSnapshots.filter(
    (item) => item.horizon === horizon && item.tradeDate === tradeDate,
  );
  const levelCounts = (['normal', 'watch', 'warning', 'critical'] as const).map(
    (level) => ({
      count: snapshots.filter((item) => item.level === level).length,
      level,
    }),
  );
  return {
    highRiskObjects: selectMockRiskObjects({ horizon, tradeDate }).filter(
      (item) =>
        item.snapshot.level === 'critical',
    ),
    horizon,
    levelCounts,
    marketSnapshot:
      snapshots.find(
        (item) =>
          item.object.objectType === 'market' && item.object.objectId === 'CN-A',
      ) ?? null,
    riskDisclaimer: RISK_DECISION_SUPPORT_NOTICE,
    tradeDate,
  };
}

export function selectMockRiskObjects(
  query: Pick<RiskObjectQuery, 'horizon' | 'tradeDate'> = {},
): RiskObjectListItem[] {
  if (!query.horizon && !query.tradeDate) return mockRiskObjects;
  return mockRiskObjects.flatMap((item) => {
    const matchingSnapshot = mockRiskSnapshots.find(
      (candidate) =>
        candidate.object.objectType === item.object.objectType &&
        candidate.object.objectId === item.object.objectId &&
        (!query.horizon || candidate.horizon === query.horizon) &&
        (!query.tradeDate || candidate.tradeDate === query.tradeDate),
    );
    return matchingSnapshot ? [{ ...item, snapshot: matchingSnapshot }] : [];
  });
}

export function selectMockRiskObjectDetail(
  objectType: RiskObjectListItem['object']['objectType'],
  objectId: string,
): RiskObjectDetail {
  const item = mockRiskObjects.find(
    (candidate) =>
      candidate.object.objectType === objectType &&
      candidate.object.objectId === objectId,
  );
  if (!item) {
    throw new Error(`未找到风险对象 ${objectType}/${objectId}`);
  }
  const parentObjects =
    objectType === 'market'
      ? []
      : objectType === 'sector'
        ? [{ objectId: 'CN-A', objectType: 'market' as const }]
        : [
            { objectId: 'CN-A', objectType: 'market' as const },
            { objectId: 'SW1:801120', objectType: 'sector' as const },
          ];
  return {
    ...item,
    activeTriggers: item.snapshot.evidence.map(
      (evidenceItem) => evidenceItem.indicatorCode,
    ),
    parentObjects,
    snapshots: mockRiskSnapshots.filter(
      (candidate) =>
        candidate.object.objectType === objectType &&
        candidate.object.objectId === objectId,
    ),
  };
}

export function selectMockRiskTrend(
  objectType: RiskObjectListItem['object']['objectType'],
  objectId: string,
  query: RiskTrendQuery = {},
): RiskTrendPoint[] {
  const horizonScale =
    query.horizon === '20-60d' ? 0.78 : query.horizon === '5-20d' ? 0.9 : 1;
  const objectScale = objectType === 'market' ? 0.82 : objectType === 'sector' ? 0.92 : 1;
  const identityOffset = [...objectId].reduce(
    (total, character) => total + character.charCodeAt(0),
    0,
  ) % 3;
  return mockRiskTrend
    .filter(
      (item) =>
        (!query.startDate || item.tradeDate >= query.startDate) &&
        (!query.endDate || item.tradeDate <= query.endDate),
    )
    .map((item) => {
      const totalScore = Math.min(
        100,
        Math.round(
          ((item.totalScore ?? 0) * horizonScale * objectScale + identityOffset) *
            10,
        ) / 10,
      );
      return {
        ...item,
        aScore: item.aScore === null ? null : item.aScore * objectScale,
        cScore: item.cScore === null ? null : item.cScore * objectScale,
        level:
          totalScore >= 65
            ? 'critical'
            : totalScore >= 60
              ? 'warning'
              : totalScore >= 50
                ? 'watch'
                : 'normal',
        totalScore,
      };
    });
}
