import { describe, expect, it } from 'vitest';

import { selectMockSignalDashboard } from '#/api/stock/mock';

import { backfillProgress, isHistoricalSignal } from './dashboard-display';

describe('signal dashboard date and backfill presentation', () => {
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
