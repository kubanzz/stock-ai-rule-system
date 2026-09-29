import { createApp, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import BacktestPage from './index.vue';

const stockApi = vi.hoisted(() => ({
  getBacktestReport: vi.fn(),
  getBacktestReportHistory: vi.fn(),
  getCandidateRules: vi.fn(),
  getRules: vi.fn(),
  getWatchlists: vi.fn(),
  runBacktest: vi.fn(),
}));

vi.mock('#/api/stock', () => ({
  ...stockApi,
  STOCK_RISK_DISCLAIMER: '回测仅供辅助决策，不保证收益。',
}));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      setup(_, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

async function renderReport(resultJson: Record<string, unknown>, metrics: { label: string; unit: string; value: null | number }[]) {
  const report = {
    cumulativeReturns: [],
    equityCurve: [],
    failureSamples: [],
    metrics,
    objectCode: 'R_TEST',
    objectType: 'rule',
    reportId: '1',
    resultJson: JSON.stringify(resultJson),
    holdingPeriod: 1,
    status: 'success',
  };
  stockApi.getBacktestReportHistory.mockResolvedValue({
    rows: [{ objectCode: 'R_TEST', objectType: 'rule', reportId: '1', status: 'skipped' }],
    total: 1,
  });
  stockApi.getBacktestReport.mockResolvedValue(report);
  stockApi.getRules.mockResolvedValue({ rows: [] });
  stockApi.getCandidateRules.mockResolvedValue({ rows: [] });
  stockApi.getWatchlists.mockResolvedValue([]);

  const container = document.createElement('div');
  document.body.append(container);
  const app = createApp(BacktestPage);
  app.mount(container);
  await vi.waitFor(() => expect(stockApi.getBacktestReport).toHaveBeenCalledWith('1'));
  await vi.waitFor(() => expect(container.textContent).toContain('所选报告状态'));
  await nextTick();
  return { app, container };
}

afterEach(() => {
  document.body.replaceChildren();
  vi.clearAllMocks();
});

describe('backtest report presentation', () => {
  it('marks watch-only runs as unevaluable and keeps null metrics blank', async () => {
    const { app, container } = await renderReport(
      { statisticsVersion: 2, signalCount: 5, directionalCount: 0, watchCount: 5, evaluatedCount: 0, unevaluableCount: 0, emptyReasonCode: 'watch_only' },
      [
        { label: '触发次数', unit: '次', value: 5 },
        { label: '胜率', unit: '%', value: null },
      ],
    );

    expect(container.textContent).toContain('方向信号：0');
    expect(container.textContent).toContain('观望：5');
    expect(container.textContent).toContain('本次信号全部为观望');
    expect(container.textContent).toContain('没有可计算的收益曲线');
    expect(container.textContent).toContain('—');
    app.unmount();
  });

  it('labels old reports with their legacy statistical meaning', async () => {
    const { app, container } = await renderReport(
      { signalCount: 5, evaluatedCount: 5 },
      [
        { label: '触发次数', unit: '次', value: 5 },
        { label: '1日胜率', unit: '%', value: 0 },
      ],
    );

    expect(container.textContent).toContain('旧报告使用原统计口径');
    expect(container.textContent).toContain('这里的 0% 不能视为真实方向胜率');
    const detailPosition = container.textContent?.indexOf('所选报告详情') ?? -1;
    const noticePosition = container.textContent?.indexOf('旧报告使用原统计口径') ?? -1;
    const metricPosition = container.textContent?.indexOf('触发次数') ?? -1;
    expect(detailPosition).toBeGreaterThanOrEqual(0);
    expect(noticePosition).toBeGreaterThan(detailPosition);
    expect(metricPosition).toBeGreaterThan(noticePosition);
    expect(container.textContent).toContain('1日胜率0%');
    expect(container.textContent).toContain('旧报告未保存可还原的逐笔收益曲线');
    expect(container.textContent).not.toContain('本次信号全部为观望');
    app.unmount();
  });

  it('evaluates rule direction even when final signals are watch in a v3 report', async () => {
    const { app, container } = await renderReport(
      {
        statisticsVersion: 3,
        evaluationBasis: 'rule_direction',
        signalCount: 5,
        directionalCount: 5,
        watchCount: 5,
        undirectedCount: 0,
        evaluatedCount: 4,
        unevaluableCount: 1,
      },
      [
        { label: '触发次数', unit: '次', value: 5 },
        { label: '1日规则方向命中率', unit: '%', value: 50 },
      ],
    );

    expect(container.textContent).toContain('1 日规则方向命中率');
    expect(container.textContent).toContain('1日规则方向命中率50%');
    expect(container.textContent).toContain('规则方向样本：5');
    expect(container.textContent).toContain('最终观望：5');
    expect(container.textContent).toContain('无规则方向：0');
    expect(container.textContent).toContain('可评估：4');
    expect(container.textContent).toContain('缺行情：1');
    expect(container.textContent).not.toContain('本次信号全部为观望');
    expect(container.textContent).not.toContain('旧报告使用原统计口径');
    app.unmount();
  });
});
