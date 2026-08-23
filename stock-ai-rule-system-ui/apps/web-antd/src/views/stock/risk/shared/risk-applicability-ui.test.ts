import type { RiskSnapshot } from '#/api/stock/risk/types';

import { createApp, nextTick } from 'vue';

import { afterEach, describe, expect, it } from 'vitest';

import RiskDimensionBars from './risk-dimension-bars.vue';

const snapshot: RiskSnapshot = {
  aScore: 70,
  cScore: 70,
  calculatedAt: '2026-08-21T20:00:00+08:00',
  completeness: 1,
  conclusionStatus: 'formal',
  dimensions: [
    {
      applicable: false,
      coverage: 0,
      dimension: 'T',
      indicators: [
        {
          availableAt: null,
          code: 'T1',
          dimension: 'T',
          name: '事件触发',
          observedAt: null,
          rawValue: null,
          reason: '当前对象类型不适用该指标',
          score: null,
          source: null,
          status: 'not_applicable',
          used: false,
          weight: 25,
        },
      ],
      score: null,
      totalCount: 0,
      usedCount: 0,
    },
  ],
  evidence: [],
  horizon: '1-5d',
  level: 'watch',
  mScore: 1,
  modelVersion: 'risk-warning-v2',
  object: { objectId: 'SW1:801010', objectType: 'sector' },
  riskConfidence: 0.8,
  sScore: 70,
  stage: 'fragile',
  tScore: null,
  totalScore: 70,
  tradeDate: '2026-08-21',
  vScore: 70,
};

afterEach(() => {
  document.body.replaceChildren();
});

describe('risk applicability UI', () => {
  it('renders non-applicable dimensions and indicators without missing-data counts', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    const app = createApp(RiskDimensionBars, { snapshot });

    app.mount(container);
    await nextTick();

    const dimensionRows = container.querySelectorAll('.grid');
    const triggerRow = dimensionRows.item(1);
    expect(triggerRow.textContent).toContain('不适用');
    expect(triggerRow.textContent).not.toContain('0/0 可用');

    const triggers = [
      ...container.querySelectorAll<HTMLButtonElement>('.indicator-trigger'),
    ];
    triggers.at(1)?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    await Promise.resolve();

    expect(document.body.textContent).toContain('当前对象类型不适用该指标');
    expect(document.body.textContent).toContain('不适用');

    app.unmount();
  });
});
