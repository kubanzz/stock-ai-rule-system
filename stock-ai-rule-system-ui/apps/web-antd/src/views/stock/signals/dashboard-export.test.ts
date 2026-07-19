import type { SignalDashboardRow } from '#/api/stock';

import { describe, expect, it } from 'vitest';

import { selectMockSignalDashboard } from '#/api/stock/mock';
import { RISK_DECISION_SUPPORT_NOTICE } from '#/api/stock/risk/types';

import { buildSignalDashboardCsv } from './dashboard-export';

function requireReadyRow(): SignalDashboardRow {
  const row = selectMockSignalDashboard({ riskHorizon: '1-5d' }).signals.find(
    (item) => item.riskSnapshot?.level === 'critical',
  );
  if (!row?.riskSnapshot) throw new Error('缺少可导出的风险快照');
  return row;
}

describe('signal dashboard CSV export', () => {
  it('exports dimensions, evidence fields and the fixed decision notice', () => {
    const csv = buildSignalDashboardCsv([requireReadyRow()], '1-5d');

    expect(csv).toContain('"V","T","S","C","A","M"');
    expect(csv).toContain(
      '"证据指标","证据来源","证据质量","证据观测时间","证据可用时间"',
    );
    expect(csv).toContain('"V:V1 | T:T2 | S:S1 | C:C2 | A:A2"');
    expect(csv).toContain('"available | available | available');
    expect(csv).toContain(`"${RISK_DECISION_SUPPORT_NOTICE}"`);
  });

  it('escapes commas, quotes and newlines in every CSV cell', () => {
    const row = requireReadyRow();
    const firstEvidence = row.riskSnapshot?.evidence[0];
    if (!row.riskSnapshot || !firstEvidence) {
      throw new Error('缺少 CSV 转义测试证据');
    }
    const escapedRow: SignalDashboardRow = {
      ...row,
      name: '贵州,"茅台"\n样例',
      riskSnapshot: {
        ...row.riskSnapshot,
        evidence: [
          { ...firstEvidence, source: 'ak,"tools"' },
          ...row.riskSnapshot.evidence.slice(1),
        ],
      },
    };

    const csv = buildSignalDashboardCsv([escapedRow], '1-5d');

    expect(csv).toContain('"贵州,""茅台""\n样例"');
    expect(csv).toContain('"ak,""tools"" | aktools');
  });
});
