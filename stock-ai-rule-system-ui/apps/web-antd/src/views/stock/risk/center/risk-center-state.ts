import type {
  RiskDataQualityStatus,
  RiskEvidence,
  RiskHorizon,
  RiskLevel,
  RiskObjectDetail,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskSnapshot,
  RiskTrendQuery,
} from '#/api/stock/risk/types';

import { riskEvidenceKey } from '../shared/presentation';

export type RiskDataState =
  | 'formal'
  | 'insufficient'
  | 'provisional'
  | 'stale'
  | 'unavailable';

export const RISK_DATA_STATE_LABELS: Record<RiskDataState, string> = {
  formal: '正式评估',
  insufficient: '数据不足',
  provisional: '暂定评估',
  stale: '数据过期',
  unavailable: '数据不可用',
};

export interface RiskHierarchy {
  market: RiskObjectListItem[];
  sectors: RiskObjectListItem[];
  stocks: RiskObjectListItem[];
}

export interface RiskObjectQueries {
  market: RiskObjectQuery;
  sector: RiskObjectQuery;
  stock: RiskObjectQuery;
}

export interface RiskTriggerTimelineItem extends RiskEvidence {
  horizon: RiskHorizon;
  key: string;
}

const LEVEL_RANK = {
  critical: 4,
  normal: 1,
  warning: 3,
  watch: 2,
} as const;

const STALE_QUALITY = new Set<RiskDataQualityStatus>(['stale']);

export function createRiskCenterQuery(): Required<
  Pick<RiskObjectQuery, 'horizon' | 'pageNum' | 'pageSize'>
> {
  return {
    horizon: '1-5d',
    pageNum: 1,
    pageSize: 100,
  };
}

export function buildRiskTrendQuery(
  query: Pick<RiskObjectQuery, 'horizon' | 'tradeDate'>,
): RiskTrendQuery {
  return {
    endDate: query.tradeDate,
    horizon: query.horizon,
  };
}

export function createRequestSequence() {
  let current = 0;
  return {
    isCurrent(requestId: number) {
      return requestId === current;
    },
    next() {
      current += 1;
      return current;
    },
  };
}

export function buildSectorMatrix(
  rows: RiskObjectListItem[],
): RiskObjectListItem[] {
  return rows
    .filter((item) => item.object.objectType === 'sector')
    .toSorted((left, right) => {
      const leftAssessment = displayRiskAssessment(left.snapshot);
      const rightAssessment = displayRiskAssessment(right.snapshot);
      const levelDelta =
        (rightAssessment.level ? LEVEL_RANK[rightAssessment.level] : 0) -
        (leftAssessment.level ? LEVEL_RANK[leftAssessment.level] : 0);
      if (levelDelta !== 0) {
        return levelDelta;
      }
      return (rightAssessment.score ?? -1) - (leftAssessment.score ?? -1);
    });
}

export function buildRiskObjectQueries(
  query: RiskObjectQuery,
  parentSectorId?: string,
): RiskObjectQueries {
  const sharedQuery = {
    horizon: query.horizon,
    keyword: query.keyword,
    level: query.level,
    tradeDate: query.tradeDate,
  };
  return {
    market: {
      ...sharedQuery,
      objectType: 'market',
      pageNum: 1,
      pageSize: 1,
    },
    sector: {
      ...sharedQuery,
      objectType: 'sector',
      pageNum: 1,
      pageSize: 100,
    },
    stock: {
      ...sharedQuery,
      objectType: 'stock',
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      parentObjectId: parentSectorId,
      parentObjectType: parentSectorId ? 'sector' : undefined,
    },
  };
}

export function buildRiskHierarchy(
  market: RiskObjectListItem[],
  sectors: RiskObjectListItem[],
  stocks: RiskObjectListItem[],
): RiskHierarchy {
  return {
    market,
    sectors,
    stocks,
  };
}

export function buildRiskTriggerTimeline(
  detail: null | RiskObjectDetail,
): RiskTriggerTimelineItem[] {
  if (!detail) {
    return [];
  }
  const activeTriggers = new Set(detail.activeTriggers);
  const uniqueEvidence = new Map<string, RiskTriggerTimelineItem>();

  for (const snapshot of detail.snapshots) {
    for (const evidence of snapshot.evidence) {
      if (!activeTriggers.has(evidence.indicatorCode)) {
        continue;
      }
      const key = `${snapshot.horizon}:${riskEvidenceKey(evidence)}`;
      uniqueEvidence.set(key, { ...evidence, horizon: snapshot.horizon, key });
    }
  }

  return [...uniqueEvidence.values()].toSorted((left, right) =>
    left.availableAt.localeCompare(right.availableAt),
  );
}

export function riskDataState(snapshot: null | RiskSnapshot): RiskDataState {
  if (snapshot?.conclusionStatus === 'formal') {
    return 'formal';
  }
  if (snapshot?.conclusionStatus === 'provisional') {
    return 'provisional';
  }
  if (snapshot?.conclusionStatus === 'unavailable') {
    return 'unavailable';
  }
  if (snapshot?.evidence.some((item) => item.qualityStatus === 'unavailable')) {
    return 'unavailable';
  }
  if (
    snapshot?.evidence.some((item) => STALE_QUALITY.has(item.qualityStatus))
  ) {
    return 'stale';
  }
  if (
    !snapshot ||
    snapshot.completeness < 0.8 ||
    snapshot.level === null ||
    snapshot.totalScore === null
  ) {
    return 'insufficient';
  }
  return 'formal';
}

export function displayRiskAssessment(snapshot: null | RiskSnapshot): {
  level: null | RiskLevel;
  provisional: boolean;
  score: null | number;
} {
  const state = riskDataState(snapshot);
  if (state === 'formal') {
    return {
      level: snapshot?.level ?? null,
      provisional: false,
      score: snapshot?.totalScore ?? null,
    };
  }
  if (state === 'provisional') {
    return {
      level: snapshot?.provisionalLevel ?? null,
      provisional: true,
      score: snapshot?.provisionalScore ?? null,
    };
  }
  return { level: null, provisional: false, score: null };
}
