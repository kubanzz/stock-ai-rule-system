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
        api.runDailyWorkflow({ dryRun: true, symbols: [], tradeDate: '2026-06-20' }),
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
});
