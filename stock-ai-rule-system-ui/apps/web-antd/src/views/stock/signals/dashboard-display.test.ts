import { describe, expect, it } from 'vitest';

import { selectMockSignalDashboard } from '#/api/stock/mock';

import {
  backfillProgress,
  isHistoricalSignal,
  signalIdentityKey,
  signalStrategyLabel,
} from './dashboard-display';

describe('signal dashboard date and backfill presentation', () => {
  it('keeps the same stock and date separate by strategy and version', () => {
    const common = { symbol: '600519.SH', signalDate: '2026-09-30' };
    const keys = [
      { ...common, strategyCode: 'P4-VOTE-001', strategyVersion: 'v1' },
      { ...common, strategyCode: 'RS_G144_G118_SZ125', strategyVersion: 'v2' },
      { ...common, strategyCode: 'P4-VOTE-001', strategyVersion: 'v2' },
    ].map(signalIdentityKey);

    expect(new Set(keys).size).toBe(3);
    expect(signalIdentityKey({ ...common, signalId: 123 })).toBe('signal:123');
    expect(signalStrategyLabel({ strategyCode: 'LEGACY' })).toBe('历史默认方案');
  });
  it('identifies an older signal against the quote baseline', () => {
    const row = selectMockSignalDashboard().signals[0];
    if (!row) throw new Error('缺少信号样例');

    expect(
      isHistoricalSignal({ ...row, signalDate: '2026-09-28' }, '2026-09-29'),
    ).toBe(true);
    expect(
      isHistoricalSignal({ ...row, signalDate: '2026-09-29' }, '2026-09-29'),
    ).toBe(false);
  });

  it('caps progress when a completed task count exceeds the expected count', () => {
    expect(
      backfillProgress({ totalTasks: 3, completedTasks: 4 } as Parameters<
        typeof backfillProgress
      >[0]),
    ).toBe(100);
  });

});
