import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const signalFile = (path: string) =>
  readFileSync(
    resolve(process.cwd(), 'apps/web-antd/src/views/stock/signals', path),
    'utf8',
  );

describe('signal dashboard risk UI contract', () => {
  it('adds the three-period selector without adding routes', () => {
    const toolbar = signalFile('components/signal-dashboard-toolbar.vue');

    expect(toolbar).toContain('RISK_HORIZON_OPTIONS');
    expect(toolbar).toContain('风险周期');
    expect(toolbar).toContain('riskHorizon');
  });

  it('reuses shared risk components in the existing signal table', () => {
    const table = signalFile('components/signal-table.vue');
    const detail = signalFile('components/signal-risk-detail.vue');

    expect(table).toContain('RiskLevelTag');
    expect(table).toContain('RiskGateTag');
    expect(table).toContain('SignalRiskDetail');
    expect(detail).toContain('RiskDimensionBars');
    expect(detail).toContain('RiskEvidenceList');
    expect(detail).toContain('风险强度分');
  });

  it('makes shadow mode and explanation-only behavior explicit', () => {
    const detail = signalFile('components/signal-risk-detail.vue');

    expect(detail).toContain('影子模式 · 未执行');
    expect(detail).toContain('原始置信度');
    expect(detail).toContain('建议置信度');
    expect(detail).toContain('不改写方向或置信度');
    expect(detail).toContain('riskGateDecision.reason');

    const table = signalFile('components/signal-table.vue');
    expect(table).toContain('record.riskGateDecision.signalDirection');
    expect(table).not.toContain("record.signal === 'bearish'");
  });

  it('shows empty, insufficient and stale states with a fixed disclaimer', () => {
    const page = signalFile('index.vue');
    const detail = signalFile('components/signal-risk-detail.vue');

    expect(page).toContain(':message="dashboard?.riskDisclaimer"');
    expect(detail).toContain('暂无风险快照');
    expect(detail).toContain('完整度未达到 80%');
    expect(detail).toContain('风险证据已过期');
  });
});
