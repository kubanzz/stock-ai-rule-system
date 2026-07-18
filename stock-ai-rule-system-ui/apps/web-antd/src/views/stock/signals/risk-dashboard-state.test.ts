import type { RiskGateDecision, RiskSnapshot } from '#/api/stock/risk';

import { describe, expect, it } from 'vitest';

import {
  getRiskSnapshotState,
  isExplanationOnlyDirection,
  RISK_HORIZON_OPTIONS,
} from './risk-dashboard-state';

const baseSnapshot: RiskSnapshot = {
  aScore: 45,
  cScore: 62,
  calculatedAt: '2026-07-18T18:10:00+08:00',
  completeness: 0.88,
  evidence: [],
  horizon: '1-5d',
  level: 'warning',
  mScore: 1,
  modelVersion: 'risk-v1.0-shadow',
  object: { objectId: '600519.SH', objectType: 'stock' },
  riskConfidence: 0.84,
  sScore: 56,
  stage: 'repricing',
  tScore: 58,
  totalScore: 63,
  tradeDate: '2026-07-18',
  vScore: 70,
};

describe('signal dashboard risk presentation state', () => {
  it('offers the three fixed risk horizons', () => {
    expect(RISK_HORIZON_OPTIONS.map((item) => item.value)).toEqual([
      '1-5d',
      '5-20d',
      '20-60d',
    ]);
  });

  it('distinguishes ready, insufficient, stale, unavailable and empty snapshots', () => {
    expect(getRiskSnapshotState(baseSnapshot)).toBe('ready');
    expect(
      getRiskSnapshotState({
        ...baseSnapshot,
        completeness: 0.58,
        level: null,
        totalScore: null,
      }),
    ).toBe('insufficient');
    expect(
      getRiskSnapshotState({
        ...baseSnapshot,
        evidence: [
          {
            availableAt: '2026-07-17T16:30:00+08:00',
            details: {},
            dimension: 'V',
            indicatorCode: 'V1',
            observedAt: '2026-07-17T15:00:00+08:00',
            qualityStatus: 'stale',
            rawValue: 18,
            score: 70,
            source: 'aktools',
          },
        ],
      }),
    ).toBe('stale');
    expect(
      getRiskSnapshotState({
        ...baseSnapshot,
        evidence: [
          {
            availableAt: '2026-07-18T16:30:00+08:00',
            details: {},
            dimension: 'V',
            indicatorCode: 'V1',
            observedAt: '2026-07-18T15:00:00+08:00',
            qualityStatus: 'unavailable',
            rawValue: null,
            score: null,
            source: 'aktools',
          },
        ],
      }),
    ).toBe('unavailable');
    expect(getRiskSnapshotState(null)).toBe('empty');
  });

  it('treats bearish and watch signals as explanation-only directions', () => {
    expect(isExplanationOnlyDirection('bearish')).toBe(true);
    expect(isExplanationOnlyDirection('watch')).toBe(true);
    expect(isExplanationOnlyDirection('bullish')).toBe(false);
  });

  it('keeps every gate decision in shadow mode at the type boundary', () => {
    const decision: RiskGateDecision = {
      calculatedAt: '2026-07-18T18:10:00+08:00',
      enforced: false,
      horizon: '1-5d',
      modelVersion: 'risk-v1.0-shadow',
      object: baseSnapshot.object,
      originalConfidence: 0.78,
      reason: '仅给出风险说明。',
      signalDirection: 'bearish',
      suggestedAction: 'notice',
      suggestedConfidence: 0.78,
      tradeDate: '2026-07-18',
    };

    expect(decision.enforced).toBe(false);
    expect(decision.suggestedConfidence).toBe(decision.originalConfidence);
  });
});
