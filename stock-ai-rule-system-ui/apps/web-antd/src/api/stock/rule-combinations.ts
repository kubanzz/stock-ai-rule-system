import type {
  StockAjaxResult,
  StockPageData,
  StockPageResult,
} from './ajax-result';

import { baseRequestClient } from '#/api/request';

import {
  normalizeStockApiError,
  unwrapAjaxResult,
  unwrapStockPageResult,
} from './ajax-result';

type RawResponse<T> = { data: T };

export type CombinationStatus = 'active' | 'disabled' | 'draft';
export type RuleGroupAggregation = 'AND' | 'OR' | 'WEIGHTED';

export interface RuleGroupMember {
  required: boolean;
  ruleCode: string;
  weight: number;
}

export interface RuleGroup {
  aggregation: RuleGroupAggregation;
  description?: string;
  groupCode: string;
  groupName: string;
  members: RuleGroupMember[];
  minMatchedRules: number;
  status: CombinationStatus;
  version: string;
}

export type RuleGroupUpsert = Omit<RuleGroup, 'status' | 'version'> & {
  status?: CombinationStatus;
};

export interface StrategyGroupMember {
  group?: RuleGroup;
  groupCode: string;
  groupVersion?: string;
  required: boolean;
  weight: number;
}

export interface RuleStrategy {
  bearishThreshold: number;
  bullishThreshold: number;
  description?: string;
  groups: StrategyGroupMember[];
  riskThreshold: number;
  status: CombinationStatus;
  usageMode?: 'auxiliary';
  researchStatus?: 'pending_final';
  strategyCode: string;
  strategyName: string;
  version: string;
}

export type RuleStrategyUpsert = Omit<RuleStrategy, 'status' | 'version'> & {
  status?: CombinationStatus;
};

export interface CombinationCopyRequest {
  newCode: string;
  newName: string;
}

const USE_STOCK_MOCK = import.meta.env.VITE_STOCK_USE_MOCK === 'true';
const mockGroups: RuleGroup[] = [];
const mockStrategies: RuleStrategy[] = [];

function clone<T>(value: T): T {
  return structuredClone(value);
}

async function getList<T>(
  path: string,
  mockRows: T[],
): Promise<StockPageData<T>> {
  if (USE_STOCK_MOCK) return { rows: clone(mockRows), total: mockRows.length };
  try {
    const response =
      await baseRequestClient.get<RawResponse<StockPageResult<T>>>(path);
    return unwrapStockPageResult(response.data);
  } catch (error) {
    throw normalizeStockApiError(error);
  }
}

async function getOne<T>(path: string, mockValue: () => T): Promise<T> {
  if (USE_STOCK_MOCK) return clone(mockValue());
  try {
    const response =
      await baseRequestClient.get<RawResponse<StockAjaxResult<T>>>(path);
    return unwrapAjaxResult(response.data);
  } catch (error) {
    throw normalizeStockApiError(error);
  }
}

async function mutate<T>(
  method: 'post' | 'put',
  path: string,
  body: object,
  mockMutation: () => T,
): Promise<T> {
  if (USE_STOCK_MOCK) return clone(mockMutation());
  try {
    const response = await baseRequestClient[method]<
      RawResponse<StockAjaxResult<T>>
    >(path, body);
    return unwrapAjaxResult(response.data);
  } catch (error) {
    throw normalizeStockApiError(error);
  }
}

function requireMock<T>(
  rows: T[],
  code: string,
  getCode: (row: T) => string,
): T {
  const row = rows.find((item) => getCode(item) === code);
  if (!row) throw new Error(`找不到配置：${code}`);
  return row;
}

export function getRuleGroups() {
  return getList('/rule-groups', mockGroups);
}

export function getRuleGroup(groupCode: string) {
  return getOne(`/rule-groups/${encodeURIComponent(groupCode)}`, () =>
    requireMock(mockGroups, groupCode, (item) => item.groupCode),
  );
}

export function getRuleGroupVersions(groupCode: string) {
  return getOne(
    `/rule-groups/${encodeURIComponent(groupCode)}/versions`,
    () => [requireMock(mockGroups, groupCode, (item) => item.groupCode)],
  );
}

export function createRuleGroup(data: RuleGroupUpsert) {
  return mutate('post', '/rule-groups', data, () => {
    if (mockGroups.some((item) => item.groupCode === data.groupCode)) {
      throw new Error('规则组编码已存在');
    }
    const row: RuleGroup = { ...clone(data), status: 'draft', version: 'v1' };
    mockGroups.push(row);
    return row;
  });
}

export function updateRuleGroup(groupCode: string, data: RuleGroupUpsert) {
  return mutate(
    'put',
    `/rule-groups/${encodeURIComponent(groupCode)}`,
    data,
    () => {
      const index = mockGroups.findIndex(
        (item) => item.groupCode === groupCode,
      );
      if (index === -1) throw new Error(`找不到规则组：${groupCode}`);
      const previous = requireMock(
        mockGroups,
        groupCode,
        (item) => item.groupCode,
      );
      const row: RuleGroup = {
        ...clone(data),
        groupCode,
        status: previous.status,
        version: `v${Number(String(previous.version).replace(/^v/, '')) + 1}`,
      };
      mockGroups[index] = row;
      return row;
    },
  );
}

export function copyRuleGroup(groupCode: string, data: CombinationCopyRequest) {
  return mutate(
    'post',
    `/rule-groups/${encodeURIComponent(groupCode)}/copy`,
    data,
    () => {
      if (mockGroups.some((item) => item.groupCode === data.newCode)) {
        throw new Error('规则组编码已存在');
      }
      const source = requireMock(
        mockGroups,
        groupCode,
        (item) => item.groupCode,
      );
      const row: RuleGroup = {
        ...clone(source),
        groupCode: data.newCode,
        groupName: data.newName,
        status: 'draft',
        version: 'v1',
      };
      mockGroups.push(row);
      return row;
    },
  );
}

export function setRuleGroupStatus(
  groupCode: string,
  status: CombinationStatus,
) {
  return mutate(
    'put',
    `/rule-groups/${encodeURIComponent(groupCode)}/status`,
    { status },
    () => {
      const row = requireMock(mockGroups, groupCode, (item) => item.groupCode);
      row.status = status;
      return row;
    },
  );
}

export function getRuleStrategies() {
  return getList('/rule-strategies', mockStrategies);
}

export function getRuleStrategy(strategyCode: string) {
  return getOne(`/rule-strategies/${encodeURIComponent(strategyCode)}`, () =>
    requireMock(mockStrategies, strategyCode, (item) => item.strategyCode),
  );
}

export function getRuleStrategyVersions(strategyCode: string) {
  return getOne(
    `/rule-strategies/${encodeURIComponent(strategyCode)}/versions`,
    () => [
      requireMock(mockStrategies, strategyCode, (item) => item.strategyCode),
    ],
  );
}

export function createRuleStrategy(data: RuleStrategyUpsert) {
  return mutate('post', '/rule-strategies', data, () => {
    if (
      mockStrategies.some((item) => item.strategyCode === data.strategyCode)
    ) {
      throw new Error('应用方案编码已存在');
    }
    const row: RuleStrategy = {
      ...clone(data),
      status: 'draft',
      version: 'v1',
    };
    mockStrategies.push(row);
    return row;
  });
}

export function updateRuleStrategy(
  strategyCode: string,
  data: RuleStrategyUpsert,
) {
  return mutate(
    'put',
    `/rule-strategies/${encodeURIComponent(strategyCode)}`,
    data,
    () => {
      const index = mockStrategies.findIndex(
        (item) => item.strategyCode === strategyCode,
      );
      if (index === -1) throw new Error(`找不到应用方案：${strategyCode}`);
      const previous = requireMock(
        mockStrategies,
        strategyCode,
        (item) => item.strategyCode,
      );
      const row: RuleStrategy = {
        ...clone(data),
        status: previous.status,
        strategyCode,
        version: `v${Number(String(previous.version).replace(/^v/, '')) + 1}`,
      };
      mockStrategies[index] = row;
      return row;
    },
  );
}

export function copyRuleStrategy(
  strategyCode: string,
  data: CombinationCopyRequest,
) {
  return mutate(
    'post',
    `/rule-strategies/${encodeURIComponent(strategyCode)}/copy`,
    data,
    () => {
      if (mockStrategies.some((item) => item.strategyCode === data.newCode)) {
        throw new Error('应用方案编码已存在');
      }
      const source = requireMock(
        mockStrategies,
        strategyCode,
        (item) => item.strategyCode,
      );
      const row: RuleStrategy = {
        ...clone(source),
        status: 'draft',
        strategyCode: data.newCode,
        strategyName: data.newName,
        version: 'v1',
      };
      mockStrategies.push(row);
      return row;
    },
  );
}

export function setRuleStrategyStatus(
  strategyCode: string,
  status: CombinationStatus,
) {
  return mutate(
    'put',
    `/rule-strategies/${encodeURIComponent(strategyCode)}/status`,
    { status },
    () => {
      const row = requireMock(
        mockStrategies,
        strategyCode,
        (item) => item.strategyCode,
      );
      row.status = status;
      return row;
    },
  );
}
