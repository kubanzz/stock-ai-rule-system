import type { RiskObjectQuery, RiskObjectType } from './types';

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
