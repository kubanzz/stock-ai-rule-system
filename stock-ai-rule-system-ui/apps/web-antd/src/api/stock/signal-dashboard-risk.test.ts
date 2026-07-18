import { describe, expect, it } from 'vitest';

import { selectMockSignalDashboard } from './mock';
import { RISK_DECISION_SUPPORT_NOTICE } from './risk/types';

describe('signal dashboard risk aggregate mock', () => {
  it.each(['1-5d', '5-20d', '20-60d'] as const)(
    'returns snapshots for the selected %s horizon',
    (riskHorizon) => {
      const dashboard = selectMockSignalDashboard({ riskHorizon });

      expect(dashboard.riskHorizon).toBe(riskHorizon);
      expect(dashboard.riskDisclaimer).toBe(RISK_DECISION_SUPPORT_NOTICE);
      expect(
        dashboard.signals
          .map((row) => row.riskSnapshot)
          .filter((snapshot) => snapshot !== null)
          .every((snapshot) => snapshot?.horizon === riskHorizon),
      ).toBe(true);
      expect(
        dashboard.signals
          .map((row) => row.riskGateDecision)
          .filter(Boolean)
          .every((decision) => decision?.horizon === riskHorizon),
      ).toBe(true);
    },
  );

  it('keeps bearish and watch confidences unchanged and explanation-only', () => {
    const dashboard = selectMockSignalDashboard({ riskHorizon: '1-5d' });
    const explanationRows = dashboard.signals.filter(
      (row) =>
        (row.signal === 'bearish' || row.signal === 'watch') &&
        row.riskGateDecision,
    );

    expect(explanationRows.map((row) => row.signal).toSorted()).toEqual([
      'bearish',
      'watch',
    ]);
    for (const row of explanationRows) {
      expect(row.riskGateDecision).toMatchObject({
        enforced: false,
        suggestedAction: 'notice',
      });
      expect(row.riskGateDecision?.suggestedConfidence).toBe(
        row.riskGateDecision?.originalConfidence,
      );
    }
  });

  it('covers insufficient, stale and empty risk data', () => {
    const dashboard = selectMockSignalDashboard({ riskHorizon: '1-5d' });

    expect(
      dashboard.signals.some(
        (row) =>
          row.riskSnapshot?.level === null &&
          (row.riskSnapshot?.completeness ?? 1) < 0.8,
      ),
    ).toBe(true);
    expect(
      dashboard.signals.some((row) =>
        row.riskSnapshot?.evidence.some(
          (item) => item.qualityStatus === 'stale',
        ),
      ),
    ).toBe(true);
    expect(dashboard.signals.some((row) => row.riskSnapshot === null)).toBe(
      true,
    );

    const invalidRows = dashboard.signals.filter(
      (row) =>
        row.riskSnapshot === null ||
        row.riskSnapshot.level === null ||
        row.riskSnapshot.completeness < 0.8 ||
        row.riskSnapshot.evidence.some(
          (item) => item.qualityStatus === 'stale',
        ),
    );
    expect(invalidRows).not.toHaveLength(0);
    expect(invalidRows.every((row) => !row.riskGateDecision)).toBe(true);
  });
});
