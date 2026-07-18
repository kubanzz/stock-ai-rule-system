import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const appFile = (path: string) =>
  readFileSync(resolve(process.cwd(), 'apps/web-antd', path), 'utf8');

describe('stock real backend contract', () => {
  it('uses the stock console product title', () => {
    const appEnv = appFile('.env');

    expect(appEnv).toContain('VITE_APP_TITLE=股票规则控制台');
    expect(appEnv).toContain('VITE_APP_NAMESPACE=stock-rule-console');
  });

  it('uses the Spring Boot backend instead of the Vben mock server', () => {
    const viteConfig = appFile('vite.config.ts');
    const devEnv = appFile('.env.development');

    expect(viteConfig).toContain("'http://localhost:8080'");
    expect(viteConfig).not.toContain('localhost:5320');
    expect(viteConfig).not.toContain(String.raw`replace(/^\/api/, '')`);
    expect(viteConfig).toContain('VITE_STOCK_BACKEND_URL');
    expect(devEnv).toContain('VITE_GLOB_API_URL=/api');
    expect(devEnv).toContain('VITE_NITRO_MOCK=false');
  });

  it('does not silently fall back to stock mock data by default', () => {
    const stockApi = appFile('src/api/stock/index.ts');

    expect(stockApi).toContain(
      "const USE_STOCK_MOCK = import.meta.env.VITE_STOCK_USE_MOCK === 'true';",
    );
    expect(stockApi).not.toContain('Stock API fallback to mock data');
  });

  it('exposes stock operation console APIs against the agreed backend paths', () => {
    const stockApi = appFile('src/api/stock/index.ts');
    const candidateCodeToken = '{candidateCode}';
    const ruleCodeToken = '{ruleCode}';
    const versionIdToken = '{versionId}';
    const candidatePublishPath = `/rules/candidates/$${candidateCodeToken}/publish`;
    const ruleVersionsPath = `/rules/$${ruleCodeToken}/versions`;
    const ruleRollbackPath = `/rules/$${ruleCodeToken}/versions/$${versionIdToken}/rollback`;

    expect(stockApi).toContain('/market-data/import/mock');
    expect(stockApi).toContain('/scheduler/daily-workflow');
    expect(stockApi).toContain('/scheduler/integration-dependencies');
    expect(stockApi).toContain(candidatePublishPath);
    expect(stockApi).toContain(ruleVersionsPath);
    expect(stockApi).toContain(ruleRollbackPath);
    expect(stockApi).not.toMatch(
      /baseRequestClient\.(?:delete|get|post|put)<[\s\S]*?>\(\s*['`]\/api\//,
    );
  });

  it('exposes stock console aggregate APIs against page-level backend paths', () => {
    const stockApi = appFile('src/api/stock/index.ts');
    const types = appFile('src/api/stock/types.ts');

    expect(stockApi).toContain('getSignalDashboard');
    expect(stockApi).toContain('/signals/dashboard');
    expect(stockApi).toContain('getWatchlists');
    expect(stockApi).toContain('/watchlists');
    expect(stockApi).toContain('addWatchlistStock');
    expect(stockApi).toContain('getWatchlistStockCandidates');
    expect(stockApi).toContain('/stock-candidates');
    expect(stockApi).toContain('addWatchlistStocksBatch');
    expect(stockApi).toContain('/stocks/batch');
    expect(stockApi).toContain('removeWatchlistStock');
    expect(stockApi).toContain('createWatchlist');
    expect(stockApi).toContain('updateWatchlist');
    expect(stockApi).toContain('deleteWatchlist');
    expect(stockApi).toContain('baseRequestClient.post<');
    expect(stockApi).toContain('baseRequestClient.put<');
    expect(types).toContain('export interface WatchlistMutationRequest');
    expect(types).toContain('pageNum: number');
    expect(types).toContain('pageSize: number');
    expect(types).toContain('availableIndustries: string[]');
    expect(types).toContain('industryStrength: IndustryStrength[]');
    expect(types).toContain('sentiment: SignalSentiment');
    expect(types).toContain('riskOverview: RiskOverview');
    expect(types).toContain('export interface SignalDashboardOverview');
    expect(types).toContain('export interface WatchlistPool');
    expect(types).toContain('export interface StockResearchDetail');
    expect(types).toContain('export interface RuleGovernanceDetail');
    expect(types).toContain('export interface BacktestReportDetail');
    expect(types).toContain('export interface AiReviewOverview');
    expect(types).toContain('export interface RunCenterOverview');
  });

  it('uses the aggregate signal dashboard and watchlist drawer UI', () => {
    const signalPage = appFile('src/views/stock/signals/index.vue');
    const stockPicker = appFile(
      'src/views/stock/signals/components/stock-picker-drawer.vue',
    );

    expect(signalPage).toContain('getSignalDashboard');
    expect(signalPage).toContain('getWatchlists');
    expect(signalPage).toContain('StockPickerDrawer');
    expect(stockPicker).toContain('getWatchlistStockCandidates');
    expect(stockPicker).toContain('addWatchlistStocksBatch');
    expect(stockPicker).not.toContain("pool.poolId === 'all'");
    expect(stockPicker).not.toContain('await addWatchlistStock(');
    expect(stockPicker).toContain('Drawer');
    expect(stockPicker).toContain('添加股票');
    expect(signalPage).toContain('RiskAlert');
    expect(signalPage).toContain(':message="dashboard?.riskDisclaimer"');
    expect(signalPage).toContain('handleStocksAdded');
    expect(
      appFile('src/views/stock/signals/components/signal-table.vue'),
    ).toContain('待生成信号');
  });

  it('builds the complete signal workbench with a real index line chart', () => {
    const signalPage = appFile('src/views/stock/signals/index.vue');
    const marketPanel = appFile(
      'src/views/stock/signals/components/market-context-panel.vue',
    );
    const components = [
      'SignalMetricGrid',
      'SignalDashboardToolbar',
      'SignalTable',
      'MarketContextPanel',
      'WatchlistManagerDrawer',
      'StockPickerDrawer',
    ];

    for (const component of components) {
      expect(signalPage).toContain(component);
    }
    expect(marketPanel).toContain('EchartsUI');
    expect(marketPanel).toContain("type: 'line'");
    expect(marketPanel).toContain('信号情绪（7 日）');
    expect(marketPanel).toContain('行业强弱');
    expect(signalPage).not.toContain('保证收益');
    expect(signalPage).not.toContain('确定性预测结果');
  });

  it('maps the remaining console pages to aggregate APIs and complete views', () => {
    const routes = appFile('src/router/routes/modules/stock.ts');
    const detailPage = appFile('src/views/stock/detail/index.vue');
    const governancePage = appFile('src/views/stock/rule-governance/index.vue');
    const backtestPage = appFile('src/views/stock/backtests/index.vue');
    const aiReviewPage = appFile('src/views/stock/ai-review/index.vue');
    const candidatesPage = appFile('src/views/stock/candidates/index.vue');
    const runCenterPage = appFile('src/views/stock/run-center/index.vue');

    expect(routes).toContain("title: '股票研究'");
    expect(routes).toContain("path: '/stock/research'");
    expect(detailPage).toContain('getStockResearchDetail');
    expect(detailPage).toContain('因子状态');
    expect(detailPage).toContain('规则触发链');
    expect(detailPage).toContain('历史预测记录');
    expect(governancePage).toContain('getRuleGovernance');
    expect(governancePage).toContain('getRuleGovernanceDetail');
    expect(governancePage).toContain('Drawer');
    expect(governancePage).toContain('候选变更');
    expect(governancePage).toContain('RiskAlert');
    expect(backtestPage).toContain('getBacktestReports');
    expect(backtestPage).toContain('规则对比');
    expect(backtestPage).toContain('失败样本');
    expect(aiReviewPage).toContain('getAiReviewOverview');
    expect(aiReviewPage).toContain('getAiMisjudgements');
    expect(aiReviewPage).toContain('createCandidateFromMisjudgement');
    expect(aiReviewPage).toContain('错误类型聚类');
    expect(candidatesPage).toContain('getCandidateRules');
    expect(candidatesPage).toContain('publishCandidateRule');
    expect(candidatesPage).toContain('RiskAlert');
    expect(runCenterPage).toContain('getRunCenterOverview');
    expect(runCenterPage).toContain('运行状态');
    expect(runCenterPage).toContain('RiskAlert');
  });

  it('adds visible stock operation console navigation entries', () => {
    const stockRoutes = appFile('src/router/routes/modules/stock.ts');

    expect(stockRoutes).toContain("name: 'StockRunCenter'");
    expect(stockRoutes).toContain("path: '/stock/run-center'");
    expect(stockRoutes).toContain("title: '运行中心'");
    expect(stockRoutes).toContain("name: 'StockRiskCenter'");
    expect(stockRoutes).toContain("path: '/stock/risks'");
    expect(stockRoutes).toContain("title: '风险中心'");
    expect(stockRoutes).toContain("name: 'StockRuleGovernance'");
    expect(stockRoutes).toContain("path: '/stock/rule-governance'");
    expect(stockRoutes).toContain("title: '规则治理'");
    expect(stockRoutes).toContain("path: '/stock/operations'");
    expect(stockRoutes).toContain("name: 'StockWorkflow'");
    expect(stockRoutes).toContain("path: '/stock/workflow'");
    expect(stockRoutes).toContain("name: 'StockRules'");
    expect(stockRoutes).toContain("path: '/stock/rules'");
    expect(stockRoutes).toContain("name: 'StockCandidates'");
    expect(stockRoutes).toContain("path: '/stock/candidates'");
    expect(stockRoutes).toContain("title: '候选规则'");
    expect(stockRoutes).toContain("name: 'StockRuleVersions'");
    expect(stockRoutes).toContain("path: '/stock/rules/:ruleCode/versions'");

    const visibleOrder = [
      "title: '信号看板'",
      "title: '风险中心'",
      "title: '股票研究'",
      "title: '规则治理'",
      "title: '回测报告'",
      "title: 'AI 复盘'",
      "title: '候选规则'",
      "title: '运行中心'",
    ].map((label) => stockRoutes.indexOf(label));

    expect(visibleOrder.every((index) => index > -1)).toBe(true);
    expect(visibleOrder.toSorted((a, b) => a - b)).toEqual(visibleOrder);
  });

  it('connects the risk center to the frozen read-only backend contract', () => {
    const stockRoutes = appFile('src/router/routes/modules/stock.ts');
    const riskApi = appFile('src/api/stock/risk/index.ts');
    const riskTypes = appFile('src/api/stock/risk/types.ts');
    const riskCenter = appFile('src/views/stock/risk/center/index.vue');
    const riskCenterState = appFile(
      'src/views/stock/risk/center/risk-center-state.ts',
    );

    expect(stockRoutes).toContain("#/views/stock/risk/center/index.vue");
    expect(riskApi).toContain('getRiskOverview');
    expect(riskApi).toContain('getRiskObjects');
    expect(riskApi).toContain('getRiskObjectDetail');
    expect(riskApi).toContain('getRiskObjectTrend');
    expect(riskApi).not.toMatch(
      /baseRequestClient\.(?:delete|post|put)<[\s\S]*?>\(\s*['`]\/risks\//,
    );
    expect(riskTypes).toContain('parentObjectType?: RiskObjectType');
    expect(riskTypes).toContain('parentObjectId?: string');
    expect(riskCenterState).toContain('parentObjectType');
    expect(riskCenterState).toContain('parentObjectId');
    expect(riskCenter).toContain('RISK_DECISION_SUPPORT_NOTICE');
  });

  it('hides detailed stock operation routes after consolidating the menu', () => {
    const stockRoutes = appFile('src/router/routes/modules/stock.ts');
    const hiddenRoutes = [
      {
        importPath: '#/views/stock/operations/index.vue',
        name: 'StockOperations',
      },
      { importPath: '#/views/stock/workflow/index.vue', name: 'StockWorkflow' },
      { importPath: '#/views/stock/rules/index.vue', name: 'StockRules' },
    ];

    for (const route of hiddenRoutes) {
      const routeIndex = stockRoutes.indexOf(route.importPath);
      const routeSnippet = stockRoutes.slice(routeIndex, routeIndex + 360);

      expect(routeIndex).toBeGreaterThan(-1);
      expect(routeSnippet).toContain(`name: '${route.name}'`);
      expect(routeSnippet).toContain('hideInMenu: true');
    }
  });

  it('passes a trade date from the signal board to stock detail analysis', () => {
    const signalPage = appFile('src/views/stock/signals/index.vue');
    const toolbar = appFile(
      'src/views/stock/signals/components/signal-dashboard-toolbar.vue',
    );
    const types = appFile('src/api/stock/types.ts');
    const detailPage = appFile('src/views/stock/detail/index.vue');

    expect(types).toContain('date?: string;');
    expect(toolbar).toContain('value-format="YYYY-MM-DD"');
    expect(signalPage).toContain(
      'query: { date: query.date ?? dashboard.value?.tradeDate }',
    );
    expect(detailPage).toContain('route.query.date');
    expect(detailPage).toContain('getStockResearchDetail');
    expect(detailPage).toContain('analysisDate.value');
  });

  it('keeps the stock console as the visible application navigation', () => {
    const routeModules = [
      appFile('src/router/routes/modules/dashboard.ts'),
      appFile('src/router/routes/modules/demos.ts'),
      appFile('src/router/routes/modules/vben.ts'),
    ].join('\n');

    expect(routeModules).not.toContain("name: 'Dashboard'");
    expect(routeModules).not.toContain("name: 'Demos'");
    expect(routeModules).not.toContain("name: 'VbenProject'");
    expect(routeModules).not.toContain("name: 'VbenAbout'");
  });
});
