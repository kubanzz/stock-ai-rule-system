import type { StockPageData, StockPageResult } from '../ajax-result';
import type {
  RiskObjectDetail,
  RiskObjectDetailQuery,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskObjectType,
  RiskOverview,
  RiskOverviewQuery,
  RiskTrendPoint,
  RiskTrendQuery,
} from './types';

import { baseRequestClient } from '#/api/request';

import { unwrapAjaxResult, unwrapStockPageResult } from '../ajax-result';
import {
  mockRiskObjectDetail,
  mockRiskObjects,
  mockRiskOverview,
  mockRiskTrend,
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

function encodePathSegment(value: string) {
  return encodeURIComponent(value);
}

export const RISK_API_PATHS = {
  detail: (objectType: RiskObjectType, objectId: string) =>
    `/risks/objects/${objectType}/${encodePathSegment(objectId)}`,
  objects: '/risks/objects',
  overview: '/risks/overview',
  trend: (objectType: RiskObjectType, objectId: string) =>
    `/risks/objects/${objectType}/${encodePathSegment(objectId)}/trend`,
} as const;

export function normalizeRiskObjectQuery(
  query: RiskObjectQuery = {},
): RiskObjectQuery {
  return {
    ...query,
    pageNum: Math.max(1, query.pageNum ?? 1),
    pageSize: Math.min(100, Math.max(1, query.pageSize ?? 20)),
  };
}

export async function getRiskOverview(
  params: RiskOverviewQuery = {},
): Promise<RiskOverview> {
  if (USE_STOCK_MOCK) {
    return mockRiskOverview;
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskOverview>>
  >(RISK_API_PATHS.overview, { params });
  return unwrapAjaxResult(response.data);
}

export async function getRiskObjects(
  query: RiskObjectQuery = {},
): Promise<StockPageData<RiskObjectListItem>> {
  const params = normalizeRiskObjectQuery(query);
  if (USE_STOCK_MOCK) {
    const filtered = mockRiskObjects.filter((item) => {
      const objectMatched = params.objectType
        ? item.object.objectType === params.objectType
        : true;
      const levelMatched = params.level
        ? item.snapshot.level === params.level
        : true;
      const keywordMatched = params.keyword
        ? `${item.name}${item.object.objectId}`
            .toLowerCase()
            .includes(params.keyword.toLowerCase())
        : true;
      return objectMatched && levelMatched && keywordMatched;
    });
    const start = ((params.pageNum ?? 1) - 1) * (params.pageSize ?? 20);
    return {
      rows: filtered.slice(start, start + (params.pageSize ?? 20)),
      total: filtered.length,
    };
  }
  const response = await baseRequestClient.get<
    RawResponse<StockPageResult<RiskObjectListItem>>
  >(RISK_API_PATHS.objects, { params });
  return unwrapStockPageResult(response.data);
}

export async function getRiskObjectDetail(
  objectType: RiskObjectType,
  objectId: string,
  params: RiskObjectDetailQuery = {},
): Promise<RiskObjectDetail> {
  if (USE_STOCK_MOCK) {
    const item = mockRiskObjects.find(
      (candidate) =>
        candidate.object.objectType === objectType &&
        candidate.object.objectId === objectId,
    );
    return item
      ? { ...mockRiskObjectDetail, ...item, snapshots: [item.snapshot] }
      : mockRiskObjectDetail;
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskObjectDetail>>
  >(RISK_API_PATHS.detail(objectType, objectId), { params });
  return unwrapAjaxResult(response.data);
}

export async function getRiskObjectTrend(
  objectType: RiskObjectType,
  objectId: string,
  params: RiskTrendQuery = {},
): Promise<RiskTrendPoint[]> {
  if (USE_STOCK_MOCK) {
    return mockRiskTrend;
  }
  const response = await baseRequestClient.get<
    RawResponse<AjaxResult<RiskTrendPoint[]>>
  >(RISK_API_PATHS.trend(objectType, objectId), { params });
  return unwrapAjaxResult(response.data);
}

export type * from './types';
export { RISK_DECISION_SUPPORT_NOTICE } from './types';
