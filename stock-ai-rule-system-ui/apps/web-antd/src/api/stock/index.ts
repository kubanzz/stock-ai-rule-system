import type {
  StockAjaxResult,
  StockPageData,
  StockPageResult,
} from './ajax-result';
import type {
  AiReviewOverview,
  AiReviewReport,
  AiReviewRequest,
  BacktestReportDetail,
  BacktestReportHistoryItem,
  BacktestReportOverview,
  BacktestRequest,
  BacktestResult,
  CandidateRule,
  CandidateRulePublishRequest,
  CandidateRuleStatusUpdate,
  CandidateRuleSuggestion,
  DailyWorkflowDependency,
  DailyWorkflowRunResult,
  DailyWorkflowTriggerRequest,
  MarketDataImportMockResult,
  MarketDataSyncRequest,
  MarketDataSyncRun,
  MisjudgementSample,
  RuleDefinition,
  RuleDefinitionUpsert,
  RuleGovernanceDetail,
  RuleGovernanceOverview,
  RuleVersion,
  RuleVersionRollbackRequest,
  RunCenterOverview,
  SignalDashboardOverview,
  SignalDashboardQuery,
  StockAnalysis,
  StockResearchDetail,
  StockSignalItem,
  StockSignalQuery,
  WatchlistBatchMutationRequest,
  WatchlistBatchMutationResult,
  WatchlistCandidate,
  WatchlistCandidateQuery,
  WatchlistMutationRequest,
  WatchlistPool,
  WatchlistStockMutationRequest,
} from './types';

import { baseRequestClient } from '#/api/request';

import { unwrapAjaxResult, unwrapStockPageResult } from './ajax-result';
import {
  mockAiReview,
  mockAiReviewOverview,
  mockAnalysis,
  mockBacktest,
  mockBacktestReportOverview,
  mockCandidates,
  mockDailyWorkflowRun,
  mockMarketDataSyncRuns,
  mockRuleGovernanceOverview,
  mockRules,
  mockRuleVersions,
  mockRunCenterOverview,
  mockSignals,
  mockStockResearchDetail,
  mockWatchlists,
  mockWorkflowDependencies,
  selectMockSignalDashboard,
} from './mock';
import { RISK_DECISION_SUPPORT_NOTICE } from './risk/types';

interface RawResponse<T> {
  data: T;
}

const USE_STOCK_MOCK = import.meta.env.VITE_STOCK_USE_MOCK === 'true';
const mockBacktestRuns = new Map<
  number,
  {
    detail: BacktestReportDetail;
    request: BacktestRequest;
    result: BacktestResult;
  }
>();
let nextMockBacktestId = 1;

async function requestOrMock<T>(
  request: () => Promise<T>,
  mock: () => T,
): Promise<T> {
  if (USE_STOCK_MOCK) {
    return mock();
  }

  return request();
}

const defaultMarketDataSyncRun: MarketDataSyncRun = {
  failed: 0,
  inserted: 0,
  scanned: 0,
  skipped: 0,
  status: 'success',
  syncType: 'daily_quote',
  updated: 0,
};

const defaultRuleVersion: RuleVersion = {
  approvalStatus: 'published',
  id: 0,
  ruleContent: '',
  ruleId: 0,
  versionNo: 'v1.0',
};

function getMockMarketDataSyncRun() {
  return mockMarketDataSyncRuns[0] ?? defaultMarketDataSyncRun;
}

function getMockRuleVersion() {
  return mockRuleVersions[0] ?? defaultRuleVersion;
}

function unwrapRowsResult<T>(
  response: StockAjaxResult<T[]> | StockPageResult<T>,
): StockPageData<T> {
  if ('rows' in response) {
    return unwrapStockPageResult(response);
  }

  const rows = unwrapAjaxResult(response);
  return {
    rows,
    total: rows.length,
  };
}

function getImportSummaryCount(
  summary: MarketDataImportMockResult[keyof MarketDataImportMockResult],
  key: 'insertedRows' | 'totalRows' | 'updatedRows',
) {
  return summary?.[key] ?? 0;
}

function getRejectedCount(
  summary: MarketDataImportMockResult[keyof MarketDataImportMockResult],
) {
  return summary?.rejectedCount ?? summary?.rejectedRows?.length ?? 0;
}

function normalizeMarketDataSyncResult(
  result: MarketDataImportMockResult | MarketDataSyncRun,
  request: MarketDataSyncRequest,
): MarketDataSyncRun {
  if ('syncType' in result && 'status' in result) {
    return result;
  }

  const batches = [result.stocks, result.dailyQuotes];
  const failed = batches.reduce(
    (total, item) => total + getRejectedCount(item),
    0,
  );
  const errors = batches
    .flatMap((item) => item?.rejectedRows ?? [])
    .map((item) => item.reason)
    .filter(Boolean) as string[];

  return {
    dataSource: 'backend-import',
    endDate: request.endDate,
    errors,
    failed,
    finishedAt: new Date().toISOString(),
    inserted: batches.reduce(
      (total, item) => total + getImportSummaryCount(item, 'insertedRows'),
      0,
    ),
    requestParams: JSON.stringify(request),
    runId: Date.now(),
    scanned: batches.reduce(
      (total, item) => total + getImportSummaryCount(item, 'totalRows'),
      0,
    ),
    skipped: 0,
    startedAt: new Date().toISOString(),
    startDate: request.startDate,
    status: failed > 0 ? 'failed' : 'success',
    syncType: 'daily_quote',
    targetSymbol: request.targetSymbol,
    triggerBy: request.triggerBy,
    triggerType: request.triggerType ?? 'manual',
    updated: batches.reduce(
      (total, item) => total + getImportSummaryCount(item, 'updatedRows'),
      0,
    ),
  };
}

export async function getStockSignals(params: StockSignalQuery = {}) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockPageResult<StockSignalItem>>
      >('/signals', { params });
      return unwrapStockPageResult(response.data);
    },
    () => {
      const rows = mockSignals.filter((item) => {
        const matchesSignal = params.signal
          ? item.signal === params.signal
          : true;
        const matchesSymbol = params.symbol
          ? item.symbol.includes(params.symbol.toUpperCase())
          : true;
        return matchesSignal && matchesSymbol;
      });
      return { rows, total: rows.length };
    },
  );
}

export async function getSignalDashboard(
  params: SignalDashboardQuery = {},
): Promise<SignalDashboardOverview> {
  const dashboard = await requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<SignalDashboardOverview>>
      >('/signals/dashboard', { params });
      return unwrapAjaxResult(response.data);
    },
    () => selectMockSignalDashboard(params),
  );
  return {
    ...dashboard,
    riskDisclaimer: RISK_DECISION_SUPPORT_NOTICE,
  };
}

export async function getWatchlists(params: { market?: string } = {}) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<WatchlistPool[]>>
      >('/watchlists', { params });
      return unwrapAjaxResult(response.data);
    },
    () => mockWatchlists,
  );
}

export async function createWatchlist(data: WatchlistMutationRequest) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<WatchlistPool>>
      >('/watchlists', data);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      market: data.market,
      poolId: `custom-${Date.now()}`,
      poolName: data.poolName,
      stocks: [],
      total: 0,
    }),
  );
}

export async function updateWatchlist(
  poolId: string,
  data: WatchlistMutationRequest,
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.put<
        RawResponse<StockAjaxResult<WatchlistPool>>
      >(`/watchlists/${poolId}`, data);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      ...(mockWatchlists.find((item) => item.poolId === poolId) ?? {
        poolId,
        stocks: [],
        total: 0,
      }),
      market: data.market,
      poolName: data.poolName,
    }),
  );
}

export async function deleteWatchlist(poolId: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.delete<
        RawResponse<StockAjaxResult<void>>
      >(`/watchlists/${poolId}`);
      return unwrapAjaxResult(response.data);
    },
    () => undefined,
  );
}

export async function addWatchlistStock(
  poolId: string,
  data: WatchlistStockMutationRequest,
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<WatchlistPool>>
      >(`/watchlists/${poolId}/stocks`, data);
      return unwrapAjaxResult(response.data);
    },
    () => {
      const pool = mockWatchlists.find((item) => item.poolId === poolId) ?? {
        market: 'A股',
        poolId,
        poolName: '我的关注',
        stocks: [],
        total: 0,
      };
      return {
        ...pool,
        stocks: [
          {
            groupName: data.groupName,
            name: data.symbol,
            selected: true,
            symbol: data.symbol,
          },
        ],
        total: 1,
      };
    },
  );
}

export async function getWatchlistStockCandidates(
  poolId: string,
  params: WatchlistCandidateQuery = {},
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockPageResult<WatchlistCandidate>>
      >(`/watchlists/${poolId}/stock-candidates`, { params });
      return unwrapStockPageResult(response.data);
    },
    () => {
      const pool = mockWatchlists.find((item) => item.poolId === poolId);
      const poolSymbols = new Set(pool?.stocks.map((stock) => stock.symbol));
      const source = mockWatchlists.find((item) => item.poolId === 'all');
      const keyword = params.keyword?.trim().toLowerCase();
      const candidates = (source?.stocks ?? [])
        .filter((stock) =>
          keyword
            ? `${stock.symbol} ${stock.name ?? ''}`
                .toLowerCase()
                .includes(keyword)
            : true,
        )
        .map((stock) => ({ ...stock, inPool: poolSymbols.has(stock.symbol) }));
      const pageNum = Math.max(1, params.pageNum ?? 1);
      const pageSize = Math.max(1, params.pageSize ?? 20);
      const start = (pageNum - 1) * pageSize;
      return {
        rows: candidates.slice(start, start + pageSize),
        total: candidates.length,
      };
    },
  );
}

export async function addWatchlistStocksBatch(
  poolId: string,
  data: WatchlistBatchMutationRequest,
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<WatchlistBatchMutationResult>>
      >(`/watchlists/${poolId}/stocks/batch`, data);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      addedSymbols: [...new Set(data.symbols)],
      failedSymbols: [],
      poolCode: poolId,
      skippedSymbols: [],
    }),
  );
}

export async function removeWatchlistStock(poolId: string, symbol: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.delete<
        RawResponse<StockAjaxResult<WatchlistPool>>
      >(`/watchlists/${poolId}/stocks/${symbol}`);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      ...(mockWatchlists.find((item) => item.poolId === poolId) ??
        mockWatchlists[0]),
      stocks: mockWatchlists
        .flatMap((item) => item.stocks)
        .filter((item) => item.symbol !== symbol),
    }),
  );
}

export async function getStockAnalysis(symbol: string, date?: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<StockAnalysis>>
      >(`/stocks/${symbol}/analysis`, { params: { date } });
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...mockAnalysis, symbol }),
  );
}

export async function getStockResearchDetail(
  symbol: string,
  date?: string,
  versionNo?: number,
  identity: {
    signalId?: number | string;
    strategyCode?: string;
    strategyVersion?: string;
  } = {},
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<StockResearchDetail>>
      >(`/stocks/${symbol}/research`, { params: { date, versionNo, ...identity } });
      return unwrapAjaxResult(response.data);
    },
    () => {
      if (versionNo && !date) {
        throw new Error('查询指定信号版本时必须提供 date');
      }
      if (!date || date === mockStockResearchDetail.signalDate) {
        if (versionNo === 1) {
          return {
            ...mockStockResearchDetail,
            confidence: 68,
            currentVersionNo: 1,
            explanation:
              'v1 历史版本仅保存信号结果与命中规则编码，详细规则轨迹未记录。',
            factorDate: null,
            factors: [],
            riskScore: null,
            ruleChain: [
              {
                condition: '详细轨迹未记录',
                contribution: null,
                ruleCode: 'R_TREND_BREAKOUT_001',
                ruleName: 'R_TREND_BREAKOUT_001',
              },
            ],
            symbol,
            trace: null,
            traceStatus: 'legacy',
          } satisfies StockResearchDetail;
        }
        if (versionNo && versionNo !== 2) {
          throw new Error(`该信号版本不存在：v${versionNo}`);
        }
        return { ...mockStockResearchDetail, symbol };
      }
      const historical = mockStockResearchDetail.history.find(
        (record) => record.date === date,
      );
      if (versionNo && (!historical || versionNo !== 1)) {
        throw new Error(`该信号版本不存在：v${versionNo}`);
      }
      return {
        ...mockStockResearchDetail,
        confidence: historical?.confidence ?? null,
        currentVersionNo: historical ? 1 : null,
        explanation: historical
          ? '该历史信号仅保存结果与命中规则编码，生成时的逐条条件、分数贡献和因子快照未记录。'
          : '该日暂无已生成的信号。',
        factorDate: null,
        factors: [],
        ruleChain: historical
          ? historical.triggeredRules.map((ruleCode) => ({
              condition: '当时的条件与贡献未记录',
              contribution: null,
              ruleCode,
              ruleName: ruleCode,
            }))
          : [],
        riskScore: null,
        signal: historical?.signal ?? 'watch',
        signalDate: historical?.date ?? null,
        signalStatus: historical ? 'ready' : 'pending',
        symbol,
        trace: null,
        traceStatus: 'legacy',
        tradeDate: date,
        versions: historical
          ? [{ availableAt: `${date}T17:00:00`, versionNo: 1 }]
          : [],
      } satisfies StockResearchDetail;
    },
  );
}

export async function getRules() {
  return requestOrMock(
    async () => {
      const response =
        await baseRequestClient.get<
          RawResponse<StockPageResult<RuleDefinition>>
        >('/rules');
      return unwrapStockPageResult(response.data);
    },
    () => ({ rows: mockRules, total: mockRules.length }),
  );
}

export async function getRuleGovernance(
  params: {
    ruleType?: string;
    source?: string;
    status?: string;
  } = {},
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<RuleGovernanceOverview>>
      >('/rules/governance', { params });
      return unwrapAjaxResult(response.data);
    },
    () => mockRuleGovernanceOverview,
  );
}

export async function getRuleGovernanceDetail(ruleCode: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<RuleGovernanceDetail>>
      >(`/rules/${ruleCode}/governance`);
      return unwrapAjaxResult(response.data);
    },
    () => {
      const rule = mockRules.find((item) => item.ruleCode === ruleCode);
      const candidate = mockCandidates.find(
        (item) => item.targetRuleCode === ruleCode,
      );
      const productionExecutable = Boolean(
        rule &&
        rule.ruleFormat === 'drools' &&
        rule.status === 'active' &&
        rule.enabled !== false,
      );
      return {
        candidateDiff: {
          currentContent: rule?.ruleContent ?? '',
          highlights: candidate ? [candidate.reason] : [],
          proposedContent: candidate?.proposedContent ?? '',
        },
        description: rule?.description ?? '基于趋势突破和量能放大的辅助规则。',
        expression: rule?.ruleContent ?? '',
        performance: mockRuleGovernanceOverview.metrics,
        relatedFactors: ['highest(close,20)', 'ma(volume,20)'],
        ruleCode,
        ruleName: rule?.ruleName ?? ruleCode,
        ruleType: rule?.ruleType ?? 'trend',
        source: rule?.createdBy ?? 'system',
        status: rule?.status ?? 'missing',
        version: rule?.version ?? 'v1.0',
        ruleFormat: rule?.ruleFormat ?? 'drools',
        enabled: rule?.enabled ?? false,
        priority: rule?.priority ?? 0,
        productionExecutable,
      } satisfies RuleGovernanceDetail;
    },
  );
}

export async function createRule(data: RuleDefinitionUpsert) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<RuleDefinition>>
      >('/rules', data);
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...data, createdBy: 'mock' }),
  );
}

export async function updateRule(ruleCode: string, data: RuleDefinitionUpsert) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.put<
        RawResponse<StockAjaxResult<RuleDefinition>>
      >(`/rules/${ruleCode}`, data);
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...data, ruleCode }),
  );
}

export async function enableRule(ruleCode: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<boolean>>
      >(`/rules/${ruleCode}/enable`);
      return unwrapAjaxResult(response.data);
    },
    () => true,
  );
}

export async function disableRule(ruleCode: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<boolean>>
      >(`/rules/${ruleCode}/disable`);
      return unwrapAjaxResult(response.data);
    },
    () => true,
  );
}

export async function getCandidateRules() {
  return requestOrMock(
    async () => {
      const response =
        await baseRequestClient.get<
          RawResponse<StockPageResult<CandidateRule>>
        >('/rules/candidates');
      return unwrapStockPageResult(response.data);
    },
    () => ({ rows: mockCandidates, total: mockCandidates.length }),
  );
}

export async function publishCandidateRule(
  candidateCode: string,
  data: CandidateRulePublishRequest,
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<RuleVersion>>
      >(`/rules/candidates/${candidateCode}/publish`, data);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      ...getMockRuleVersion(),
      changeReason: data.reason,
      createdBy: data.operator ?? 'mock',
      source: candidateCode,
    }),
  );
}

export async function updateCandidateRuleStatus(
  candidateCode: string,
  data: CandidateRuleStatusUpdate,
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.put<
        RawResponse<StockAjaxResult<CandidateRule>>
      >(`/rules/candidates/${candidateCode}/status`, data);
      return unwrapAjaxResult(response.data);
    },
    () => {
      const candidate =
        mockCandidates.find((item) => item.candidateCode === candidateCode) ??
        mockCandidates[0] ??
        ({
          candidateCode,
          changeType: 'observe',
          originalContent: '',
          proposedContent: '',
          reason: '',
          source: 'mock',
          status: 'candidate',
          targetRuleCode: '',
        } satisfies CandidateRule);
      return { ...candidate, candidateCode, status: data.status };
    },
  );
}

export async function runBacktest(data: BacktestRequest) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<BacktestResult>>
      >('/backtests', data, { timeout: 600_000 });
      return unwrapAjaxResult(response.data);
    },
    () => {
      const id = nextMockBacktestId++;
      const combination =
        data.objectType === 'rule_group' || data.objectType === 'strategy';
      const resultJson = JSON.stringify({
        directionalCount: mockBacktestReportOverview.sampleCount,
        evaluationBasis: combination ? 'combination_signal' : 'rule_direction',
        equityCurve: mockBacktestReportOverview.equityCurve,
        evaluatedCount: mockBacktestReportOverview.evaluatedCount,
        signalCount: mockBacktestReportOverview.sampleCount,
        statisticsVersion: 3,
        undirectedCount: 0,
        unevaluableCount: mockBacktestReportOverview.unevaluableCount,
        watchCount: 0,
      });
      const result: BacktestResult = {
        ...mockBacktest,
        ...data,
        id,
        resultJson,
        status: 'success',
      };
      const detail: BacktestReportDetail = {
        cumulativeReturns: mockBacktestReportOverview.cumulativeReturns,
        createdTime: new Date().toISOString(),
        endDate: data.endDate,
        equityCurve: mockBacktestReportOverview.equityCurve,
        evaluatedCount: mockBacktestReportOverview.evaluatedCount,
        failureSamples: mockBacktestReportOverview.failureSamples,
        holdingPeriod: data.holdingPeriod,
        metrics: mockBacktestReportOverview.metrics.map((metric) =>
          metric.label === '胜率'
            ? {
                ...metric,
                label: combination
                  ? `${data.holdingPeriod}日组合信号命中率`
                  : `${data.holdingPeriod}日规则方向命中率`,
              }
            : metric,
        ),
        objectCode: data.objectCode,
        objectType: data.objectType,
        reportId: String(id),
        resultJson,
        sampleCount: mockBacktestReportOverview.sampleCount,
        startDate: data.startDate,
        status: 'success',
        stockPoolCode: data.stockPoolCode,
        stockPoolType: data.stockPoolType,
        symbols: data.symbols ?? [],
        unevaluableCount: mockBacktestReportOverview.unevaluableCount,
      };
      mockBacktestRuns.set(id, { detail, request: data, result });
      return result;
    },
  );
}

export async function getBacktestReportHistory(
  params: {
    endDate?: string;
    holdingPeriod?: number;
    market?: string;
    objectCode?: string;
    objectType?: BacktestRequest['objectType'];
    pageNum?: number;
    pageSize?: number;
    startDate?: string;
    stockPoolCode?: string;
    stockPoolType?: BacktestRequest['stockPoolType'];
    symbols?: string[];
  } = {},
): Promise<StockPageData<BacktestReportHistoryItem>> {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockPageResult<BacktestReportHistoryItem>>
      >('/backtests/reports/history', { params, paramsSerializer: 'repeat' });
      return unwrapStockPageResult(response.data);
    },
    () => {
      const normalizedSymbols = (symbols: string[]) =>
        [
          ...new Set(symbols.map((symbol) => symbol.trim().toUpperCase())),
        ].toSorted();
      const rows = [...mockBacktestRuns.values()]
        .toReversed()
        .filter(
          ({ request }) =>
            (!params.objectType || request.objectType === params.objectType) &&
            (!params.objectCode || request.objectCode === params.objectCode) &&
            (!params.startDate || request.startDate >= params.startDate) &&
            (!params.endDate || request.endDate <= params.endDate) &&
            (params.holdingPeriod === null ||
              params.holdingPeriod === undefined ||
              request.holdingPeriod === params.holdingPeriod) &&
            (!params.stockPoolType ||
              request.stockPoolType === params.stockPoolType) &&
            (!params.stockPoolCode ||
              request.stockPoolCode === params.stockPoolCode) &&
            (!params.symbols ||
              JSON.stringify(normalizedSymbols(request.symbols ?? [])) ===
                JSON.stringify(normalizedSymbols(params.symbols))),
        )
        .map(
          ({ detail, result }): BacktestReportHistoryItem => ({
            avgReturn: result.avgReturn,
            createdTime: detail.createdTime,
            endDate: detail.endDate,
            evaluatedCount: detail.evaluatedCount,
            holdingPeriod: detail.holdingPeriod,
            maxDrawdown: result.maxDrawdown,
            objectCode: detail.objectCode,
            objectType: detail.objectType,
            reportId: detail.reportId,
            sampleCount: detail.sampleCount,
            startDate: detail.startDate,
            status: detail.status,
            stockPoolCode: detail.stockPoolCode,
            stockPoolType: detail.stockPoolType,
            symbols: detail.symbols,
            totalReturn: result.totalReturn,
            triggerCount: result.triggerCount,
            unevaluableCount: detail.unevaluableCount,
            winRate: result.winRate,
          }),
        );
      const pageNum = Math.max(1, params.pageNum ?? 1);
      const pageSize = Math.max(1, params.pageSize ?? 20);
      return {
        rows: rows.slice((pageNum - 1) * pageSize, pageNum * pageSize),
        total: rows.length,
      };
    },
  );
}

export async function getBacktestReports(
  params: {
    endDate?: string;
    holdingPeriod?: number;
    market?: string;
    objectCode?: string;
    objectType?: BacktestRequest['objectType'];
    startDate?: string;
    stockPoolCode?: string;
    stockPoolType?: BacktestRequest['stockPoolType'];
    symbols?: string[];
  } = {},
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<BacktestReportOverview>>
      >('/backtests/reports', { params, paramsSerializer: 'repeat' });
      return unwrapAjaxResult(response.data);
    },
    () => {
      const matches = [...mockBacktestRuns.values()]
        .toReversed()
        .find(
          ({ request }) =>
            (!params.objectType || params.objectType === request.objectType) &&
            (!params.objectCode || params.objectCode === request.objectCode) &&
            (!params.startDate || params.startDate <= request.startDate) &&
            (!params.endDate || params.endDate >= request.endDate) &&
            (params.holdingPeriod === null ||
              params.holdingPeriod === undefined ||
              params.holdingPeriod === request.holdingPeriod) &&
            (!params.stockPoolType ||
              params.stockPoolType === request.stockPoolType) &&
            (!params.stockPoolCode ||
              params.stockPoolCode === request.stockPoolCode) &&
            (!params.symbols ||
              JSON.stringify(params.symbols) ===
                JSON.stringify(request.symbols ?? [])),
        );
      if (!matches) {
        return {
          ...mockBacktestReportOverview,
          comparison: [],
          cumulativeReturns: [],
          equityCurve: [],
          evaluatedCount: 0,
          failureSamples: [],
          metrics: [],
          reportId: undefined,
          sampleCount: 0,
          status: undefined,
          unevaluableCount: 0,
        };
      }
      return {
        ...mockBacktestReportOverview,
        comparison: [],
        cumulativeReturns: matches.detail.cumulativeReturns,
        equityCurve: matches.detail.equityCurve,
        failureSamples: matches.detail.failureSamples,
        metrics: matches.detail.metrics,
        reportId: matches.detail.reportId,
        resultJson: matches.detail.resultJson,
        sampleCount: matches.detail.sampleCount,
        evaluatedCount: matches.detail.evaluatedCount,
        unevaluableCount: matches.detail.unevaluableCount,
        status: matches.detail.status,
      };
    },
  );
}

export async function getBacktestReport(reportId: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<BacktestReportDetail>>
      >(`/backtests/reports/${reportId}`);
      return unwrapAjaxResult(response.data);
    },
    () => {
      const detail = mockBacktestRuns.get(Number(reportId))?.detail;
      if (!detail) throw new Error(`模拟回测报告不存在：${reportId}`);
      return detail;
    },
  );
}

export async function syncMarketData(data: MarketDataSyncRequest) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<
          StockAjaxResult<MarketDataImportMockResult | MarketDataSyncRun>
        >
      >(
        '/market-data/import/mock',
        {},
        {
          params: {
            endDate: data.endDate,
            startDate: data.startDate,
            symbol: data.targetSymbol,
          },
        },
      );
      return normalizeMarketDataSyncResult(
        unwrapAjaxResult(response.data),
        data,
      );
    },
    () =>
      ({
        ...getMockMarketDataSyncRun(),
        ...data,
        runId: Date.now(),
        status: 'success' as const,
        triggerType: data.triggerType ?? 'manual',
      }) satisfies MarketDataSyncRun,
  );
}

export async function runDailyWorkflow(data: DailyWorkflowTriggerRequest) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<DailyWorkflowRunResult>>
      >('/scheduler/daily-workflow', data);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      ...mockDailyWorkflowRun,
      dryRun: data.dryRun,
      symbols: data.symbols,
      tradeDate: data.tradeDate,
    }),
  );
}

export async function getIntegrationDependencies() {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<DailyWorkflowDependency[]>>
      >('/scheduler/integration-dependencies');
      return unwrapAjaxResult(response.data);
    },
    () => mockWorkflowDependencies,
  );
}

export async function getRuleVersions(ruleCode: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<
          StockAjaxResult<RuleVersion[]> | StockPageResult<RuleVersion>
        >
      >(`/rules/${ruleCode}/versions`);
      return unwrapRowsResult(response.data);
    },
    () => ({ rows: mockRuleVersions, total: mockRuleVersions.length }),
  );
}

export async function rollbackRuleVersion(
  ruleCode: string,
  versionId: number | string,
  data: RuleVersionRollbackRequest,
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<RuleVersion>>
      >(`/rules/${ruleCode}/versions/${versionId}/rollback`, data);
      return unwrapAjaxResult(response.data);
    },
    () => ({
      ...getMockRuleVersion(),
      approvalStatus: 'rolled_back' as const,
      changeReason: data.reason,
      createdBy: data.operator ?? 'mock',
      id: Number(versionId),
    }),
  );
}

export async function runAiReview(data: AiReviewRequest) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<AiReviewReport>>
      >('/ai/review', data);
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...mockAiReview, reviewDate: data.date, symbol: data.symbol }),
  );
}

export async function getAiReviewOverview(date?: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<AiReviewOverview>>
      >('/ai/reviews/summary', { params: { date } });
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...mockAiReviewOverview, reviewDate: date }),
  );
}

export async function getAiMisjudgements(
  params: {
    date?: string;
    reasonCategory?: string;
  } = {},
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<MisjudgementSample[]>>
      >('/ai/reviews/misjudgements', { params });
      return unwrapAjaxResult(response.data);
    },
    () => mockAiReviewOverview.misjudgements,
  );
}

export async function createCandidateFromMisjudgement(sampleId: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.post<
        RawResponse<StockAjaxResult<CandidateRuleSuggestion>>
      >(`/ai/reviews/misjudgements/${sampleId}/candidate-rule`);
      return unwrapAjaxResult(response.data);
    },
    () =>
      mockAiReviewOverview.candidateSuggestion ?? {
        actionRequired: '需回测',
        candidateCode: `CAND-${sampleId}`,
        oldCondition: '',
        priority: 'medium',
        proposedCondition: '',
        targetRuleCode: '',
      },
  );
}

export async function getRunCenterOverview(date?: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<RunCenterOverview>>
      >('/run-center/overview', { params: { date } });
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...mockRunCenterOverview, tradeDate: date }),
  );
}

export * from './mock';
export * from './signal-backfill';
export * from './types';
