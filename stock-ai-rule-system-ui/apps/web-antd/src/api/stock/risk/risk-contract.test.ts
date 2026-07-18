import { describe, expect, it } from 'vitest';

import {
  normalizeRiskObjectQuery,
  RISK_API_PATHS,
} from './contract';
import {
  mockIncompleteRiskObject,
  mockRiskOverview,
  mockRiskSnapshots,
  selectMockRiskObjectDetail,
  selectMockRiskObjects,
  selectMockRiskOverview,
  selectMockRiskTrend,
} from './mock';
import { RISK_DECISION_SUPPORT_NOTICE } from './types';

describe('risk api contract', () => {
  it('uses backend paths without duplicating the /api prefix', () => {
    expect(RISK_API_PATHS.overview).toBe('/risks/overview');
    expect(RISK_API_PATHS.objects).toBe('/risks/objects');
    expect(RISK_API_PATHS.detail('sector', 'SW1:801010')).toBe(
      '/risks/objects/sector/SW1%3A801010',
    );
    expect(RISK_API_PATHS.trend('stock', '600519.SH')).toBe(
      '/risks/objects/stock/600519.SH/trend',
    );
  });

  it('caps pagination at 100 and keeps camelCase query fields', () => {
    expect(
      normalizeRiskObjectQuery({
        horizon: '1-5d',
        objectType: 'stock',
        pageNum: 0,
        pageSize: 500,
      }),
    ).toEqual({
      horizon: '1-5d',
      objectType: 'stock',
      pageNum: 1,
      pageSize: 100,
    });
  });

  it('normalizes non-finite and fractional pagination safely', () => {
    expect(
      normalizeRiskObjectQuery({ pageNum: Number.NaN, pageSize: Infinity }),
    ).toMatchObject({ pageNum: 1, pageSize: 20 });
    expect(normalizeRiskObjectQuery({ pageNum: 2.9, pageSize: 10.8 })).toMatchObject(
      { pageNum: 2, pageSize: 10 },
    );
  });

  it('keeps directions, risk levels and gates independent', () => {
    const item = mockRiskOverview.highRiskObjects.find(
      (candidate) => candidate.object.objectId === '600519.SH',
    );
    expect(item?.snapshot.level).toBe('critical');
    expect(item?.gateDecision?.suggestedAction).toBe('block');
    expect(item?.gateDecision?.signalDirection).toBe('bullish');
    expect(item?.gateDecision?.enforced).toBe(false);
  });

  it('represents insufficient data without manufacturing a zero score', () => {
    expect(mockIncompleteRiskObject.snapshot.completeness).toBeLessThan(0.8);
    expect(mockIncompleteRiskObject.snapshot.totalScore).toBeNull();
    expect(mockIncompleteRiskObject.snapshot.level).toBeNull();
    expect(mockIncompleteRiskObject.snapshot.stage).toBeNull();
    expect(mockIncompleteRiskObject.snapshot.riskConfidence).toBeNull();
  });

  it('keeps the fixed notice outside individual snapshots', () => {
    expect('riskDisclaimer' in mockRiskSnapshots[0]!).toBe(false);
    expect(mockRiskOverview.riskDisclaimer).toBe(RISK_DECISION_SUPPORT_NOTICE);
  });

  it('carries three horizons, evidence lineage and the fixed notice', () => {
    expect(new Set(mockRiskSnapshots.map((item) => item.horizon))).toEqual(
      new Set(['1-5d', '5-20d', '20-60d']),
    );
    const evidence = mockRiskSnapshots.flatMap((item) => item.evidence);
    expect(evidence.every((item) => item.observedAt && item.availableAt)).toBe(
      true,
    );
    expect(mockRiskOverview.riskDisclaimer).toBe(
      RISK_DECISION_SUPPORT_NOTICE,
    );
    expect(RISK_DECISION_SUPPORT_NOTICE).toBe(
      '风险预警仅用于辅助决策，不构成投资建议，不保证收益；风险分是综合指标分数，不代表事件发生概率。',
    );
  });

  it('returns all three horizons for stock detail fixtures', () => {
    expect(
      new Set(
        mockRiskSnapshots
          .filter((item) => item.object.objectId === '600519.SH')
          .map((item) => item.horizon),
      ),
    ).toEqual(new Set(['1-5d', '5-20d', '20-60d']));
  });

  it('builds identity-safe details and rejects unknown mock objects', () => {
    const market = selectMockRiskObjectDetail('market', 'CN-A');
    expect(market.object).toEqual({ objectId: 'CN-A', objectType: 'market' });
    expect(market.parentObjects).toEqual([]);
    expect(market.gateDecision).toBeUndefined();
    expect(() => selectMockRiskObjectDetail('sector', 'SW1:UNKNOWN')).toThrow(
      '未找到风险对象',
    );
  });

  it('honors horizon, object identity and date range in mock selectors', () => {
    const longOverview = selectMockRiskOverview({ horizon: '20-60d' });
    expect(longOverview.horizon).toBe('20-60d');
    expect(longOverview.marketSnapshot?.horizon).toBe('20-60d');
    expect(selectMockRiskObjects({ horizon: '20-60d' })).toHaveLength(2);

    const marketTrend = selectMockRiskTrend('market', 'CN-A', {
      endDate: '2026-07-17',
      horizon: '20-60d',
      startDate: '2026-07-16',
    });
    const stockTrend = selectMockRiskTrend('stock', '600519.SH', {
      endDate: '2026-07-17',
      horizon: '1-5d',
      startDate: '2026-07-16',
    });
    expect(marketTrend).toHaveLength(2);
    expect(marketTrend[0]?.totalScore).not.toBe(stockTrend[0]?.totalScore);
  });
});
