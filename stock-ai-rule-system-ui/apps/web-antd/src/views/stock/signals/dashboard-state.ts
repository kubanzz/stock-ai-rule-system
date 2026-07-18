import type { SignalDashboardQuery } from '#/api/stock';

export type DashboardQueryState = SignalDashboardQuery & {
  pageNum: number;
  pageSize: number;
};

export function createDashboardQuery(): DashboardQueryState {
  return {
    market: 'A股',
    pageNum: 1,
    pageSize: 20,
    poolCode: 'my-follow',
    sortField: 'confidence',
    sortOrder: 'desc',
  };
}

export function applyDashboardFilters(
  current: DashboardQueryState,
  patch: Partial<SignalDashboardQuery>,
): DashboardQueryState {
  return {
    ...current,
    ...patch,
    pageNum: 1,
  };
}

export function applyDashboardPagination(
  current: DashboardQueryState,
  pageNum: number,
  pageSize: number,
): DashboardQueryState {
  return {
    ...current,
    pageNum: Math.max(1, pageNum),
    pageSize: Math.max(1, pageSize),
  };
}

export function applyStocksAdded(
  current: DashboardQueryState,
  poolCode: string,
): DashboardQueryState {
  return {
    ...current,
    confidenceMax: undefined,
    confidenceMin: undefined,
    industry: undefined,
    pageNum: 1,
    poolCode,
    signal: undefined,
    symbol: undefined,
  };
}
