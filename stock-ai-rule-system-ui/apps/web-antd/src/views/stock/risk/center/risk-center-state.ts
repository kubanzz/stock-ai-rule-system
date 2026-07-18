import type {
  RiskDataQualityStatus,
  RiskEvidence,
  RiskHorizon,
  RiskObjectDetail,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskSnapshot,
} from '#/api/stock/risk/types';

export type RiskDataState = 'insufficient' | 'ready' | 'stale';

export interface RiskHierarchy {
  market: RiskObjectListItem[];
  sectors: RiskObjectListItem[];
  stocks: RiskObjectListItem[];
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

const STALE_QUALITY = new Set<RiskDataQualityStatus>(['stale', 'unavailable']);

export function createRiskCenterQuery(): Required<
  Pick<RiskObjectQuery, 'horizon' | 'pageNum' | 'pageSize'>
> {
  return {
    horizon: '1-5d',
    pageNum: 1,
    pageSize: 100,
  };
}

export function buildSectorMatrix(
  rows: RiskObjectListItem[],
): RiskObjectListItem[] {
  return rows
    .filter((item) => item.object.objectType === 'sector')
    .toSorted((left, right) => {
      const levelDelta =
        (right.snapshot.level ? LEVEL_RANK[right.snapshot.level] : 0) -
        (left.snapshot.level ? LEVEL_RANK[left.snapshot.level] : 0);
      if (levelDelta !== 0) {
        return levelDelta;
      }
      return (
        (right.snapshot.totalScore ?? -1) - (left.snapshot.totalScore ?? -1)
      );
    });
}

export function buildRiskHierarchy(
  rows: RiskObjectListItem[],
  selected?: RiskObjectListItem,
): RiskHierarchy {
  let sectors = rows.filter((item) => item.object.objectType === 'sector');
  let stocks = rows.filter((item) => item.object.objectType === 'stock');

  if (selected?.object.objectType === 'sector') {
    stocks = stocks.filter((item) => item.parentName === selected.name);
  } else if (selected?.object.objectType === 'stock' && selected.parentName) {
    sectors = sectors.filter((item) => item.name === selected.parentName);
    stocks = stocks.filter((item) => item.parentName === selected.parentName);
  }

  return {
    market: rows.filter((item) => item.object.objectType === 'market'),
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
      const key = [
        snapshot.horizon,
        evidence.indicatorCode,
        evidence.availableAt,
        evidence.source,
      ].join(':');
      uniqueEvidence.set(key, { ...evidence, horizon: snapshot.horizon, key });
    }
  }

  return [...uniqueEvidence.values()].toSorted((left, right) =>
    left.availableAt.localeCompare(right.availableAt),
  );
}

export function riskDataState(snapshot: null | RiskSnapshot): RiskDataState {
  if (
    !snapshot ||
    snapshot.completeness < 0.8 ||
    snapshot.level === null ||
    snapshot.totalScore === null
  ) {
    return 'insufficient';
  }
  if (snapshot.evidence.some((item) => STALE_QUALITY.has(item.qualityStatus))) {
    return 'stale';
  }
  return 'ready';
}
