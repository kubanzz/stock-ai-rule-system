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
  mockSignalDashboard,
  mockSignals,
  mockStockResearchDetail,
  mockWatchlists,
  mockWorkflowDependencies,
} from './mock';

interface RawResponse<T> {
  data: T;
}

const USE_STOCK_MOCK = import.meta.env.VITE_STOCK_USE_MOCK === 'true';

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
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<SignalDashboardOverview>>
      >('/signals/dashboard', { params });
      return unwrapAjaxResult(response.data);
    },
    () => mockSignalDashboard,
  );
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

export async function getStockResearchDetail(symbol: string, date?: string) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<StockResearchDetail>>
      >(`/stocks/${symbol}/research`, { params: { date } });
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...mockStockResearchDetail, symbol }),
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
    () => ({
      candidateDiff: {
        currentContent: 'close > highest(close, 20)',
        highlights: ['新增量能过滤', '提高突破阈值'],
        proposedContent:
          'close > highest(close, 20) * 1.002 AND volume > ma(volume, 20) * 1.5',
      },
      description: '基于趋势突破和量能放大的辅助规则。',
      expression: 'close > highest(close, 20)',
      performance: mockRuleGovernanceOverview.metrics,
      relatedFactors: ['highest(close,20)', 'ma(volume,20)'],
      ruleCode,
      ruleName: ruleCode,
      ruleType: 'trend',
      source: 'system',
      status: 'active',
      version: 'v1.0',
    }),
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
      >('/backtests', data);
      return unwrapAjaxResult(response.data);
    },
    () => ({ ...mockBacktest, ...data }),
  );
}

export async function getBacktestReports(
  params: {
    market?: string;
    objectCode?: string;
  } = {},
) {
  return requestOrMock(
    async () => {
      const response = await baseRequestClient.get<
        RawResponse<StockAjaxResult<BacktestReportOverview>>
      >('/backtests/reports', { params });
      return unwrapAjaxResult(response.data);
    },
    () => mockBacktestReportOverview,
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
    () => ({
      cumulativeReturns: mockBacktestReportOverview.cumulativeReturns,
      endDate: '2026-06-20',
      failureSamples: mockBacktestReportOverview.failureSamples,
      metrics: mockBacktestReportOverview.metrics,
      objectCode: reportId,
      objectType: 'rule',
      reportId,
      startDate: '2024-01-01',
    }),
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
export * from './types';
