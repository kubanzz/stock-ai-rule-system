import { describe, expect, it } from 'vitest';

import {
  applyDashboardFilters,
  applyDashboardPagination,
  applyStocksAdded,
  createDashboardQuery,
} from './dashboard-state';

describe('signal dashboard query state', () => {
  it('creates stable defaults for the server-side dashboard query', () => {
    expect(createDashboardQuery()).toEqual({
      market: 'A股',
      pageNum: 1,
      pageSize: 20,
      poolCode: 'my-follow',
      riskHorizon: '1-5d',
      sortField: 'confidence',
      sortOrder: 'desc',
    });
  });

  it('switches the risk horizon without changing the signal direction filter', () => {
    const current = {
      ...createDashboardQuery(),
      pageNum: 4,
      signal: 'bearish' as const,
    };

    expect(
      applyDashboardFilters(current, { riskHorizon: '20-60d' }),
    ).toMatchObject({
      pageNum: 1,
      riskHorizon: '20-60d',
      signal: 'bearish',
    });
  });

  it('resets page number when any business filter changes', () => {
    const current = {
      ...createDashboardQuery(),
      industry: '银行',
      pageNum: 3,
      symbol: '600519',
    };

    expect(applyDashboardFilters(current, { signal: 'bullish' })).toMatchObject(
      {
        industry: '银行',
        pageNum: 1,
        pageSize: 20,
        signal: 'bullish',
        symbol: '600519',
      },
    );
    expect(applyDashboardFilters(current, { market: '港股' })).toMatchObject({
      market: '港股',
      pageNum: 1,
      poolCode: 'my-follow',
    });
  });

  it('changes only pagination fields for a table page event', () => {
    const current = {
      ...createDashboardQuery(),
      industry: '银行',
      signal: 'watch' as const,
      symbol: '600519',
    };

    expect(applyDashboardPagination(current, 4, 50)).toEqual({
      ...current,
      pageNum: 4,
      pageSize: 50,
    });
  });

  it('switches to the target pool and clears filters after stocks are added', () => {
    const current = {
      ...createDashboardQuery(),
      confidenceMin: 0.6,
      pageNum: 4,
      poolCode: 'all',
      signal: 'bullish' as const,
      symbol: '600519',
    };

    expect(applyStocksAdded(current, 'my-follow')).toEqual({
      ...current,
      confidenceMax: undefined,
      confidenceMin: undefined,
      industry: undefined,
      pageNum: 1,
      poolCode: 'my-follow',
      signal: undefined,
      symbol: undefined,
    });
  });
});
