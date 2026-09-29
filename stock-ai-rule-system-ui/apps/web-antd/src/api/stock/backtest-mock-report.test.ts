import { afterEach, describe, expect, it, vi } from 'vitest';

const requestMocks = {
  get: vi.fn(),
  post: vi.fn(),
};

vi.mock('#/api/request', () => ({
  baseRequestClient: requestMocks,
}));

describe('mock backtest report', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.clearAllMocks();
  });

  it('returns a detail report for the exact mock run and object', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');
    vi.resetModules();
    const api = await import('./index');
    const request = {
      endDate: '2026-06-20',
      holdingPeriod: 1,
      objectCode: 'CR_20260620_001',
      objectType: 'candidate_rule' as const,
      startDate: '2026-01-01',
      stockPoolCode: 'my-follow',
      stockPoolType: 'watchlist' as const,
    };

    const result = await api.runBacktest(request);
    expect(result.id).toEqual(expect.any(Number));
    expect(result.objectCode).toBe(request.objectCode);

    const detail = await api.getBacktestReport(String(result.id));
    expect(detail).toMatchObject({
      objectCode: request.objectCode,
      objectType: request.objectType,
      reportId: String(result.id),
      sampleCount: 842,
      status: 'success',
    });
    expect(JSON.parse(detail.resultJson ?? '{}')).toMatchObject({
      evaluatedCount: 842,
      signalCount: 842,
    });

    const overview = await api.getBacktestReports(request);
    expect(overview.reportId).toBe(String(result.id));
    expect((await api.getBacktestReports({ ...request, objectCode: 'OTHER' })).reportId)
      .toBeUndefined();
    await expect(api.getBacktestReport('999')).rejects.toThrow('模拟回测报告不存在');
    expect(requestMocks.get).not.toHaveBeenCalled();
    expect(requestMocks.post).not.toHaveBeenCalled();
  });

  it('lists saved mock runs in descending order with paging and keeps older details', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');
    vi.resetModules();
    const api = await import('./index');
    const first = await api.runBacktest({
      endDate: '2025-12-31',
      holdingPeriod: 3,
      objectCode: 'R_OLD',
      objectType: 'rule',
      startDate: '2025-01-01',
      stockPoolCode: 'custom',
      stockPoolType: 'custom',
      symbols: ['600519.SH', '000001.SZ'],
    });
    const second = await api.runBacktest({
      endDate: '2026-06-20',
      holdingPeriod: 1,
      objectCode: 'R_NEW',
      objectType: 'rule',
      startDate: '2026-01-01',
      stockPoolCode: 'A股',
      stockPoolType: 'market',
    });

    const newest = await api.getBacktestReportHistory({ pageNum: 1, pageSize: 1 });
    expect(newest.total).toBe(2);
    expect(newest.rows.map((row) => row.reportId)).toEqual([String(second.id)]);
    const older = await api.getBacktestReportHistory({ pageNum: 2, pageSize: 1 });
    expect(older.rows[0]).toMatchObject({
      holdingPeriod: 3,
      objectCode: 'R_OLD',
      reportId: String(first.id),
      stockPoolType: 'custom',
    });
    expect(await api.getBacktestReport(String(first.id))).toMatchObject({
      objectCode: 'R_OLD',
      reportId: String(first.id),
    });
    expect((await api.getBacktestReportHistory({ objectCode: 'R_NEW' })).rows)
      .toHaveLength(1);
    expect((await api.getBacktestReportHistory({
      stockPoolType: 'custom',
      symbols: ['000001.sz', '600519.sh'],
    })).rows[0]?.reportId).toBe(String(first.id));
  });

  it('preserves the real backend response without using mock report data', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'false');
    vi.resetModules();
    const api = await import('./index');
    const request = {
      endDate: '2026-06-20',
      holdingPeriod: 1,
      objectCode: 'R_REAL',
      objectType: 'rule' as const,
      startDate: '2026-01-01',
    };
    const result = { ...api.mockBacktest, ...request, id: 701 };
    const detail = {
      cumulativeReturns: [],
      failureSamples: [],
      metrics: [],
      objectCode: 'R_REAL',
      objectType: 'rule',
      reportId: '701',
    };
    requestMocks.post.mockResolvedValue({ data: { code: 200, data: result } });
    requestMocks.get.mockResolvedValue({ data: { code: 200, data: detail } });

    expect(await api.runBacktest(request)).toBe(result);
    expect(await api.getBacktestReport('701')).toBe(detail);
    expect(requestMocks.post).toHaveBeenCalledWith('/backtests', request);
    expect(requestMocks.get).toHaveBeenCalledWith('/backtests/reports/701');
  });
});
