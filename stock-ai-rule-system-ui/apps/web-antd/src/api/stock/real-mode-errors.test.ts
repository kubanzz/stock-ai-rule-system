import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMocks = {
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
};

vi.mock('#/api/request', () => ({
  baseRequestClient: requestMocks,
}));

async function loadStockApi() {
  vi.resetModules();
  return import('./index');
}

describe('stock api request mode', () => {
  beforeEach(() => {
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  it.each([
    [
      'research analysis',
      'get',
      (api: typeof import('./index')) => api.getStockAnalysis('AAPL'),
    ],
    [
      'research detail',
      'get',
      (api: typeof import('./index')) => api.getStockResearchDetail('AAPL'),
    ],
    [
      'backtest',
      'post',
      (api: typeof import('./index')) =>
        api.runBacktest({
          endDate: '2026-06-20',
          holdingPeriod: 5,
          objectCode: 'R_TREND_BREAKOUT_001',
          objectType: 'rule',
          startDate: '2026-06-01',
        }),
    ],
    [
      'workflow',
      'post',
      (api: typeof import('./index')) =>
        api.runDailyWorkflow({
          dryRun: true,
          symbols: [],
          tradeDate: '2026-06-20',
        }),
    ],
    [
      'workflow dependencies',
      'get',
      (api: typeof import('./index')) => api.getIntegrationDependencies(),
    ],
    [
      'run center',
      'get',
      (api: typeof import('./index')) => api.getRunCenterOverview('2026-06-20'),
    ],
  ] as const)(
    'propagates %s errors in real mode without using mock data',
    async (_name, method: 'get' | 'post', call) => {
      const error = new Error('stock backend unavailable');
      requestMocks.get.mockRejectedValue(error);
      requestMocks.post.mockRejectedValue(error);

      const api = await loadStockApi();

      await expect(call(api)).rejects.toBe(error);
      expect(requestMocks[method]).toHaveBeenCalled();
    },
  );

  it('uses stock mock data only when explicitly enabled', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');

    const api = await loadStockApi();

    await expect(api.getStockAnalysis('MSFT')).resolves.toMatchObject({
      symbol: 'MSFT',
    });
    await expect(api.getIntegrationDependencies()).resolves.toEqual(
      expect.any(Array),
    );
    expect(requestMocks.get).not.toHaveBeenCalled();
    expect(requestMocks.post).not.toHaveBeenCalled();
  });

  it('passes the selected research date and version to the backend', async () => {
    requestMocks.get.mockResolvedValue({
      data: { code: 200, data: { currentVersionNo: 2, versions: [] } },
    });

    const api = await loadStockApi();
    await api.getStockResearchDetail('AAPL', '2026-06-20', 2);

    expect(requestMocks.get).toHaveBeenCalledWith('/stocks/AAPL/research', {
      params: { date: '2026-06-20', versionNo: 2 },
    });
  });

  it('passes the selected strategy and signal identity to the research backend', async () => {
    requestMocks.get.mockResolvedValue({ data: { code: 200, data: { versions: [] } } });
    const api = await loadStockApi();
    await api.getStockResearchDetail('600519.SH', '2026-09-30', undefined, {
      signalId: '812',
      strategyCode: 'P4-VOTE-001',
      strategyVersion: 'v1',
    });

    expect(requestMocks.get).toHaveBeenCalledWith('/stocks/600519.SH/research', {
      params: {
        date: '2026-09-30',
        versionNo: undefined,
        signalId: '812',
        strategyCode: 'P4-VOTE-001',
        strategyVersion: 'v1',
      },
    });
  });

  it('returns the matching mock version without borrowing newer evidence', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');

    const api = await loadStockApi();
    const version = await api.getStockResearchDetail('AAPL', '2026-06-20', 1);

    expect(version.currentVersionNo).toBe(1);
    expect(version.versions.map((item) => item.versionNo)).toEqual([2, 1]);
    expect(version.trace).toBeNull();
    expect(version.traceStatus).toBe('legacy');
    expect(version.factors).toEqual([]);
    expect(version.ruleChain[0]?.contribution).toBeNull();
  });

  it('separates production definitions from candidate records in governance mock data', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');

    const api = await loadStockApi();
    const overview = await api.getRuleGovernance();
    const production = overview.rules.filter(
      (rule) => rule.productionExecutable,
    );

    expect(production.length).toBeGreaterThan(0);
    expect(
      production.every(
        (rule) =>
          rule.ruleFormat === 'drools' &&
          rule.status === 'active' &&
          rule.enabled !== false,
      ),
    ).toBe(true);
    expect(overview.rules.some((rule) => !rule.productionExecutable)).toBe(
      true,
    );
    expect(
      overview.metrics.find((metric) => metric.label === '候选记录')?.value,
    ).toBeGreaterThan(0);

    const inactive = overview.rules.find((rule) => !rule.productionExecutable);
    expect(inactive).toBeDefined();
    if (!inactive) throw new Error('缺少未参与生产的模拟规则');
    const detail = await api.getRuleGovernanceDetail(inactive.ruleCode);
    expect(detail).toMatchObject({
      productionExecutable: false,
      ruleCode: inactive.ruleCode,
      status: inactive.status,
    });
  });

  it('passes governance filters through and preserves the backend execution flag', async () => {
    const response = {
      metrics: [],
      riskDisclaimer: '仅用于辅助决策',
      rules: [
        {
          enabled: true,
          productionExecutable: true,
          ruleCode: 'CR_PUBLISHED_001',
          ruleFormat: 'drools',
          status: 'active',
        },
      ],
    };
    requestMocks.get.mockResolvedValue({ data: { code: 200, data: response } });

    const api = await loadStockApi();
    await expect(api.getRuleGovernance({ status: 'active' })).resolves.toEqual(
      response,
    );
    expect(requestMocks.get).toHaveBeenCalledWith('/rules/governance', {
      params: { status: 'active' },
    });
  });
});
