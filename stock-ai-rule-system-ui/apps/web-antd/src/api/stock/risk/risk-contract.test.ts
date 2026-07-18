import { describe, expect, it } from 'vitest';

import {
  normalizeRiskObjectQuery,
  RISK_API_PATHS,
} from './index';
import {
  mockIncompleteRiskObject,
  mockRiskOverview,
  mockRiskSnapshots,
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

  it('keeps directions, risk levels and gates independent', () => {
    const snapshot = mockRiskSnapshots.find(
      (item) => item.object.objectId === '600519.SH',
    );
    expect(snapshot?.level).toBe('critical');
    expect(snapshot?.gateStatus).toBe('block');
    expect(snapshot?.signalDirection).toBe('bullish');
    expect(snapshot?.gateEnforced).toBe(false);
  });

  it('represents insufficient data without manufacturing a zero score', () => {
    expect(mockIncompleteRiskObject.snapshot.completeness).toBeLessThan(0.8);
    expect(mockIncompleteRiskObject.snapshot.totalScore).toBeNull();
    expect(mockIncompleteRiskObject.snapshot.level).toBeNull();
    expect(mockIncompleteRiskObject.snapshot.stage).toBeNull();
    expect(mockIncompleteRiskObject.snapshot.riskConfidence).toBeNull();
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
    expect(RISK_DECISION_SUPPORT_NOTICE).toContain('辅助决策');
    expect(RISK_DECISION_SUPPORT_NOTICE).not.toContain('保证收益');
  });
});
