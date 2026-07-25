import type { StockPageData, StockPageResult } from '../ajax-result';
import type {
  RiskObjectDetail,
  RiskObjectDetailQuery,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskObjectType,
  RiskOverview,
  RiskOverviewQuery,
  RiskSyncJob,
  RiskSyncStatus,
  RiskTrendPoint,
  RiskTrendQuery,
} from './types';

import { baseRequestClient } from '#/api/request';

import { unwrapAjaxResult, unwrapStockPageResult } from '../ajax-result';
import { normalizeRiskObjectQuery, RISK_API_PATHS } from './contract';
import {
  selectMockRiskObjectDetail,
  selectMockRiskObjects,
  selectMockRiskOverview,
  selectMockRiskTrend,
} from './mock';

interface RawResponse<T> {
  data: T;
}

interface AjaxResult<T> {
  code: number;
  data?: T;
  msg?: string;
}

const USE_STOCK_MOCK = import.meta.env.VITE_STOCK_USE_MOCK === 'true';
const RISK_REQUEST_TIMEOUT_MS = 30_000;

export async function getRiskOverview(
  params: RiskOverviewQuery = {},
): Promise<RiskOverview> {
  if (USE_STOCK_MOCK) {
    return selectMockRiskOverview(params);
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskOverview>>
  >(RISK_API_PATHS.overview, {
    params,
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

export async function getRiskObjects(
  query: RiskObjectQuery = {},
): Promise<StockPageData<RiskObjectListItem>> {
  const params = normalizeRiskObjectQuery(query);
  if (USE_STOCK_MOCK) {
    const filtered = selectMockRiskObjects(params);
    const start = ((params.pageNum ?? 1) - 1) * (params.pageSize ?? 20);
    return {
      rows: filtered.slice(start, start + (params.pageSize ?? 20)),
      total: filtered.length,
    };
  }
  const response = await baseRequestClient.get<
    RawResponse<StockPageResult<RiskObjectListItem>>
  >(RISK_API_PATHS.objects, {
    params,
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapStockPageResult(response.data);
}

export async function getRiskObjectDetail(
  objectType: RiskObjectType,
  objectId: string,
  params: RiskObjectDetailQuery = {},
): Promise<RiskObjectDetail> {
  if (USE_STOCK_MOCK) {
    return selectMockRiskObjectDetail(objectType, objectId, params);
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskObjectDetail>>
  >(RISK_API_PATHS.detail(objectType, objectId), {
    params,
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

export async function getRiskObjectTrend(
  objectType: RiskObjectType,
  objectId: string,
  params: RiskTrendQuery = {},
): Promise<RiskTrendPoint[]> {
  if (USE_STOCK_MOCK) {
    return selectMockRiskTrend(objectType, objectId, params);
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskTrendPoint[]>>
  >(RISK_API_PATHS.trend(objectType, objectId), {
    params,
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

export async function startRiskMarketSync(): Promise<RiskSyncJob> {
  if (USE_STOCK_MOCK) {
    return mockSyncJob('market:CN-A');
  }
  const response = await baseRequestClient.post<
    RawResponse<AjaxResult<RiskSyncJob>>
  >(RISK_API_PATHS.syncMarket, undefined, {
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

export async function startRiskStockSync(symbol: string): Promise<RiskSyncJob> {
  if (USE_STOCK_MOCK) {
    return mockSyncJob(`stock:${symbol}`);
  }
  const response = await baseRequestClient.post<
    RawResponse<AjaxResult<RiskSyncJob>>
  >(RISK_API_PATHS.syncStock(symbol), undefined, {
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

export async function getRiskSyncJob(jobId: string): Promise<RiskSyncJob> {
  if (USE_STOCK_MOCK) {
    return { ...mockSyncJob('market:CN-A'), jobId, status: 'succeeded' };
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskSyncJob>>
  >(RISK_API_PATHS.syncJob(jobId), {
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

export async function getRiskSyncStatus(): Promise<RiskSyncStatus> {
  if (USE_STOCK_MOCK) {
    return { activeJobs: [], latestMarketJob: null };
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskSyncStatus>>
  >(RISK_API_PATHS.syncStatus, {
    timeout: RISK_REQUEST_TIMEOUT_MS,
  });
  return unwrapAjaxResult(response.data);
}

function mockSyncJob(scopeKey: string): RiskSyncJob {
  return {
    createdAt: new Date().toISOString(),
    eventCount: 0,
    evidenceCount: 0,
    finishedAt: null,
    jobId: `mock-${Date.now()}`,
    message: null,
    observationCount: 0,
    phase: 'resolving_trade_date',
    progress: 0,
    scopeKey,
    snapshotCount: 0,
    startedAt: null,
    status: 'queued',
    tradeDate: null,
    unavailableDatasetCount: 0,
  };
}

export { normalizeRiskObjectQuery, RISK_API_PATHS } from './contract';
export type * from './types';
export { RISK_DECISION_SUPPORT_NOTICE } from './types';
