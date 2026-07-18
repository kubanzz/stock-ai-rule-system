import type {
  RiskObjectDetail,
  RiskObjectListItem,
  RiskSnapshot,
} from '#/api/stock/risk/types';

import { describe, expect, it } from 'vitest';

import {
  buildRiskHierarchy,
  buildRiskObjectQueries,
  buildRiskTrendQuery,
  buildRiskTriggerTimeline,
  buildSectorMatrix,
  createRequestSequence,
  createRiskCenterQuery,
  riskDataState,
} from './risk-center-state';

function snapshot(overrides: Partial<RiskSnapshot> = {}): RiskSnapshot {
  return {
    aScore: 58,
    cScore: 67,
    calculatedAt: '2026-07-18T18:00:00+08:00',
    completeness: 0.9,
    evidence: [],
    horizon: '1-5d',
    level: 'warning',
    mScore: 1.05,
    modelVersion: 'risk-v1',
    object: { objectId: 'CN-A', objectType: 'market' },
    riskConfidence: 0.84,
    sScore: 59,
    stage: 'repricing',
    tScore: 55,
    totalScore: 63.4,
    tradeDate: '2026-07-18',
    vScore: 72,
    ...overrides,
  };
}

function object(
  name: string,
  objectType: RiskObjectListItem['object']['objectType'],
  objectId: string,
  score: null | number,
  level: RiskSnapshot['level'],
): RiskObjectListItem {
  const itemSnapshot = snapshot({
    level,
    object: { objectId, objectType },
    totalScore: score,
  });
  return { name, object: itemSnapshot.object, snapshot: itemSnapshot };
}

describe('risk center state', () => {
  it('creates a bounded default query for the short horizon', () => {
    expect(createRiskCenterQuery()).toEqual({
      horizon: '1-5d',
      pageNum: 1,
      pageSize: 100,
    });
  });

  it('caps historical trend queries at the selected trade date', () => {
    expect(
      buildRiskTrendQuery({
        ...createRiskCenterQuery(),
        tradeDate: '2026-07-16',
      }),
    ).toEqual({ endDate: '2026-07-16', horizon: '1-5d' });
  });

  it('accepts only the latest async request token', () => {
    const sequence = createRequestSequence();
    const oldRequest = sequence.next();
    const latestRequest = sequence.next();

    expect(sequence.isCurrent(oldRequest)).toBe(false);
    expect(sequence.isCurrent(latestRequest)).toBe(true);
  });

  it('builds a sector-only matrix ordered by severity and score', () => {
    const rows = [
      object('A 股', 'market', 'CN-A', 70, 'critical'),
      object('食品饮料', 'sector', 'SW1:801120', 66, 'warning'),
      object('农林牧渔', 'sector', 'SW1:801010', 72, 'critical'),
      object('贵州茅台', 'stock', '600519.SH', 80, 'critical'),
    ];

    expect(buildSectorMatrix(rows).map((item) => item.object.objectId)).toEqual(
      ['SW1:801010', 'SW1:801120'],
    );
  });

  it('keeps the market-sector-stock drilldown levels distinct', () => {
    const hierarchy = buildRiskHierarchy(
      [object('A 股', 'market', 'CN-A', 60, 'warning')],
      [object('食品饮料', 'sector', 'SW1:801120', 62, 'warning')],
      [object('贵州茅台', 'stock', '600519.SH', 69, 'critical')],
    );

    expect(hierarchy.market).toHaveLength(1);
    expect(hierarchy.sectors).toHaveLength(1);
    expect(hierarchy.stocks).toHaveLength(1);
  });

  it('builds separate object queries with a stable sector parent id', () => {
    const queries = buildRiskObjectQueries(
      {
        ...createRiskCenterQuery(),
        keyword: '茅台',
        tradeDate: '2026-07-18',
      },
      'SW1:801120',
    );

    expect(queries.market).toMatchObject({
      objectType: 'market',
      pageNum: 1,
      pageSize: 1,
    });
    expect(queries.sector).toMatchObject({
      objectType: 'sector',
      pageNum: 1,
      pageSize: 100,
    });
    expect(queries.stock).toMatchObject({
      keyword: '茅台',
      objectType: 'stock',
      parentObjectId: 'SW1:801120',
      parentObjectType: 'sector',
      tradeDate: '2026-07-18',
    });
  });

  it('builds an active trigger timeline with point-in-time lineage', () => {
    const detail: RiskObjectDetail = {
      activeTriggers: ['C2', 'A2'],
      name: '贵州茅台',
      object: { objectId: '600519.SH', objectType: 'stock' },
      parentObjects: [
        { objectId: 'CN-A', objectType: 'market' },
        { objectId: 'SW1:801120', objectType: 'sector' },
      ],
      snapshot: snapshot({
        evidence: [],
        object: { objectId: '600519.SH', objectType: 'stock' },
      }),
      snapshots: [
        snapshot({
          evidence: [
            {
              availableAt: '2026-07-18T16:30:00+08:00',
              details: {},
              dimension: 'A',
              indicatorCode: 'A2',
              observedAt: '2026-07-18T15:30:00+08:00',
              qualityStatus: 'available',
              rawValue: -12,
              score: 71,
              source: 'aktools',
            },
            {
              availableAt: '2026-07-18T15:40:00+08:00',
              details: {},
              dimension: 'C',
              indicatorCode: 'C2',
              observedAt: '2026-07-18T15:00:00+08:00',
              qualityStatus: 'available',
              rawValue: 0.28,
              score: 68,
              source: 'aktools',
            },
            {
              availableAt: '2026-07-18T14:00:00+08:00',
              details: {},
              dimension: 'V',
              indicatorCode: 'V1',
              observedAt: '2026-07-18T13:30:00+08:00',
              qualityStatus: 'available',
              rawValue: 18,
              score: 65,
              source: 'aktools',
            },
          ],
          object: { objectId: '600519.SH', objectType: 'stock' },
        }),
      ],
    };

    const timeline = buildRiskTriggerTimeline(detail);

    expect(timeline.map((item) => item.indicatorCode)).toEqual(['C2', 'A2']);
    expect(timeline[0]).toMatchObject({
      availableAt: '2026-07-18T15:40:00+08:00',
      observedAt: '2026-07-18T15:00:00+08:00',
      source: 'aktools',
    });
  });

  it('does not turn incomplete or stale evidence into a formal risk state', () => {
    expect(
      riskDataState(
        snapshot({ completeness: 0.72, level: null, totalScore: null }),
      ),
    ).toBe('insufficient');
    expect(
      riskDataState(
        snapshot({
          evidence: [
            {
              availableAt: '2026-07-18T16:00:00+08:00',
              details: {},
              dimension: 'C',
              indicatorCode: 'C2',
              observedAt: '2026-07-18T15:00:00+08:00',
              qualityStatus: 'stale',
              rawValue: null,
              score: null,
              source: 'aktools',
            },
          ],
        }),
      ),
    ).toBe('stale');
    expect(
      riskDataState(
        snapshot({
          evidence: [
            {
              availableAt: '2026-07-18T16:00:00+08:00',
              details: {},
              dimension: 'C',
              indicatorCode: 'C2',
              observedAt: '2026-07-18T15:00:00+08:00',
              qualityStatus: 'unavailable',
              rawValue: null,
              score: null,
              source: 'aktools',
            },
          ],
          level: null,
          totalScore: null,
        }),
      ),
    ).toBe('unavailable');
  });
});
