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

vi.mock('#/api/stock/rule-combinations', () => ({
  getRuleGroups: vi.fn().mockResolvedValue({ rows: [], total: 0 }),
  getRuleStrategies: vi.fn().mockResolvedValue({ rows: [], total: 0 }),
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

async function renderReport(
  resultJson: Record<string, unknown>,
  metrics: { label: string; unit: string; value: null | number }[],
  objectType = 'rule',
) {
  const report = {
    cumulativeReturns: [],
    equityCurve: [],
    failureSamples: [],
    metrics,
    objectCode: 'R_TEST',
    objectType,
    reportId: '1',
    resultJson: JSON.stringify(resultJson),
    holdingPeriod: 1,
    status: 'success',
  };
  stockApi.getBacktestReportHistory.mockResolvedValue({
    rows: [
      { objectCode: 'R_TEST', objectType, reportId: '1', status: 'skipped' },
    ],
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
  await vi.waitFor(() =>
    expect(stockApi.getBacktestReport).toHaveBeenCalledWith('1'),
  );
  await vi.waitFor(() =>
    expect(container.textContent).toContain('所选报告状态'),
  );
  await nextTick();
  return { app, container };
}

afterEach(() => {
  document.body.replaceChildren();
  vi.clearAllMocks();
});

describe('backtest report presentation', () => {
  it('explains the saved combination report basis', async () => {
    const { app, container } = await renderReport(
      {
        statisticsVersion: 3,
        evaluationBasis: 'combination_signal',
        signalCount: 5,
        directionalCount: 3,
        watchCount: 2,
        evaluatedCount: 3,
        combinationSnapshot: {
          strategyCode: 'S_TREND',
          strategyName: '趋势量能策略',
          version: 'v2',
          bullishThreshold: 55,
          bearishThreshold: 60,
          riskThreshold: 80,
          groups: [
            {
              groupCode: 'G_TREND',
              groupVersion: 'v3',
              required: true,
              weight: 2,
              group: {
                groupCode: 'G_TREND',
                groupName: '趋势确认',
                version: 'v3',
                aggregation: 'AND',
                minMatchedRules: 1,
                members: [
                  {
                    ruleCode: 'R_SHARED',
                    ruleVersionNo: 'v5',
                    required: true,
                    weight: 1,
                  },
                ],
              },
            },
            {
              groupCode: 'G_VOLUME',
              groupVersion: 'v1',
              required: false,
              weight: 1,
              group: {
                groupCode: 'G_VOLUME',
                groupName: '量能确认',
                version: 'v1',
                aggregation: 'WEIGHTED',
                minMatchedRules: 1,
                members: [
                  {
                    ruleCode: 'R_SHARED',
                    ruleVersionNo: 'v5',
                    required: false,
                    weight: 2,
                  },
                ],
              },
            },
          ],
        },
        executedRuleVersions: { R_SHARED: 'v6' },
        combinationContributions: [
          {
            date: '2026-09-01',
            symbol: '600519.SH',
            signal: 'bullish',
            bullishScore: 61,
            bearishScore: 0,
            riskScore: 5,
            trace: {
              requiredGroupGatePassed: true,
              unmetRequiredGroups: [],
              groupContributions: [
                {
                  groupCode: 'G_TREND',
                  groupVersion: 'v3',
                  aggregation: 'AND',
                  required: true,
                  eligible: true,
                  matchedCount: 1,
                  memberCount: 1,
                  weightedScores: { bullish: 60, bearish: 0, risk: 5 },
                  countedRules: ['R_SHARED'],
                },
              ],
              ruleContributions: [
                {
                  ruleCode: 'R_SHARED',
                  actualRuleVersion: 'v6',
                  matchStatus: 'MATCHED',
                  directionGroupCode: 'G_TREND',
                  riskGroupCode: 'G_TREND',
                  directionWeight: 2,
                  riskWeight: 2,
                  originalScores: { bullish: 30, bearish: 0, risk: 2.5 },
                  weightedScores: { bullish: 60, bearish: 0, risk: 5 },
                },
              ],
            },
          },
        ],
      },
      [{ label: '1日组合信号命中率', unit: '%', value: 66.67 }],
      'strategy',
    );

    expect(container.textContent).toContain(
      '组合信号命中率只统计最终给出看涨或看跌方向',
    );
    expect(container.textContent).toContain('趋势量能策略 · v2');
    expect(container.textContent).toContain('G_TREND · v3');
    expect(container.textContent).toContain('R_SHARED · 配置时版本 v5 · 执行 v6');
    expect(container.textContent).toContain('跨组重复规则：R_SHARED');
    expect(container.textContent).toContain('逐样本聚合贡献示例');
    expect(container.textContent).toContain('G_TREND · v3 · AND · 成立');
    expect(container.textContent).toContain('R_SHARED（方向→G_TREND');
    app.unmount();
  });

  it('keeps old combination reports readable without a complete snapshot', async () => {
    const { app, container } = await renderReport(
      {
        statisticsVersion: 3,
        evaluationBasis: 'combination_signal',
        signalCount: 1,
        combinationSnapshot: { strategyCode: 'S_OLD', version: 'v1' },
        combinationContributions: [{ date: '2026-09-01', symbol: 'AAPL' }],
      },
      [],
      'strategy',
    );
    expect(container.textContent).toContain('此报告没有可展示的组合配置快照');
    expect(container.textContent).not.toContain('逐样本聚合贡献示例');
    app.unmount();
  });

  it('marks watch-only runs as unevaluable and keeps null metrics blank', async () => {
    const { app, container } = await renderReport(
      {
        statisticsVersion: 2,
        signalCount: 5,
        directionalCount: 0,
        watchCount: 5,
        evaluatedCount: 0,
        unevaluableCount: 0,
        emptyReasonCode: 'watch_only',
      },
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
    const noticePosition =
      container.textContent?.indexOf('旧报告使用原统计口径') ?? -1;
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
