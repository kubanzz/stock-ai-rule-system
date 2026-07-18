import type {
  RiskDataQualityStatus,
  RiskDimension,
  RiskGateDecision,
  RiskHorizon,
  RiskLevel,
  RiskSnapshot,
  SignalDirection,
} from './risk/types';
import type {
  AiReviewOverview,
  AiReviewReport,
  BacktestReportOverview,
  BacktestResult,
  CandidateRule,
  DailyWorkflowDependency,
  DailyWorkflowRunResult,
  MarketDataSyncRun,
  RuleDefinition,
  RuleGovernanceOverview,
  RuleVersion,
  RunCenterOverview,
  SignalDashboardOverview,
  SignalDashboardQuery,
  SignalDashboardRow,
  StockAnalysis,
  StockResearchDetail,
  StockSignalItem,
  WatchlistPool,
} from './types';

import { RISK_DECISION_SUPPORT_NOTICE } from './risk/types';

export const STOCK_RISK_DISCLAIMER =
  '本系统输出仅作为股票研究和辅助决策信号，不构成投资建议，不代表确定性预测，也不保证收益。';

export const mockSignals: StockSignalItem[] = [
  {
    bearishScore: 24,
    bullishScore: 72,
    confidence: 0.68,
    currentPrice: 195.3,
    name: 'Apple Inc.',
    riskDisclaimer: STOCK_RISK_DISCLAIMER,
    riskScore: 45,
    signal: 'bullish',
    signalLevel: '偏强',
    suggestedPeriod: '3-5 个交易日',
    symbol: 'AAPL',
    triggeredRuleCount: 3,
    updatedAt: '2026-06-20 17:00',
  },
  {
    bearishScore: 38,
    bullishScore: 55,
    confidence: 0.61,
    currentPrice: 449.1,
    name: 'Microsoft',
    riskDisclaimer: STOCK_RISK_DISCLAIMER,
    riskScore: 72,
    signal: 'watch',
    signalLevel: '观察',
    suggestedPeriod: '1-3 个交易日',
    symbol: 'MSFT',
    triggeredRuleCount: 3,
    updatedAt: '2026-06-20 17:00',
  },
  {
    bearishScore: 69,
    bullishScore: 31,
    confidence: 0.64,
    currentPrice: 172.6,
    name: 'Tesla',
    riskDisclaimer: STOCK_RISK_DISCLAIMER,
    riskScore: 83,
    signal: 'high_risk',
    signalLevel: '高风险',
    suggestedPeriod: '盘后复核',
    symbol: 'TSLA',
    triggeredRuleCount: 4,
    updatedAt: '2026-06-20 17:00',
  },
  {
    bearishScore: 66,
    bullishScore: 28,
    confidence: 0.59,
    currentPrice: 128.4,
    name: 'NVIDIA',
    riskDisclaimer: STOCK_RISK_DISCLAIMER,
    riskScore: 64,
    signal: 'bearish',
    signalLevel: '偏弱',
    suggestedPeriod: '3 个交易日',
    symbol: 'NVDA',
    triggeredRuleCount: 2,
    updatedAt: '2026-06-20 17:00',
  },
];

type DashboardRiskState = 'empty' | 'insufficient' | 'ready' | 'stale';

interface DashboardRiskSeed {
  gateMode: 'none' | 'shadow';
  riskState: DashboardRiskState;
  row: Omit<SignalDashboardRow, 'riskGateDecision' | 'riskSnapshot'>;
  signalDirection: SignalDirection;
}

const dashboardRiskSeeds: DashboardRiskSeed[] = [
  {
    gateMode: 'shadow',
    riskState: 'ready',
    row: {
      bearishScore: 18,
      bullishScore: 76,
      changePct: 1.24,
      confidence: 0.78,
      name: '贵州茅台',
      price: 1486.2,
      quoteStatus: 'ready',
      riskScore: 69.5,
      signal: 'bullish',
      signalStatus: 'ready',
      suggestedPeriod: '3-5 个交易日',
      symbol: '600519.SH',
      triggeredRuleCount: 5,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'bullish',
  },
  {
    gateMode: 'shadow',
    riskState: 'ready',
    row: {
      bearishScore: 68,
      bullishScore: 24,
      changePct: -2.16,
      confidence: 0.72,
      name: '宁德时代',
      price: 214.35,
      quoteStatus: 'ready',
      riskScore: 61,
      signal: 'bearish',
      signalStatus: 'ready',
      suggestedPeriod: '1-3 个交易日',
      symbol: '300750.SZ',
      triggeredRuleCount: 4,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'bearish',
  },
  {
    gateMode: 'shadow',
    riskState: 'ready',
    row: {
      bearishScore: 42,
      bullishScore: 44,
      changePct: -0.18,
      confidence: 0.61,
      name: '工商银行',
      price: 7.18,
      quoteStatus: 'ready',
      riskScore: 48,
      signal: 'watch',
      signalStatus: 'ready',
      suggestedPeriod: '盘后复核',
      symbol: '601398.SH',
      triggeredRuleCount: 2,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'watch',
  },
  {
    gateMode: 'shadow',
    riskState: 'ready',
    row: {
      bearishScore: 64,
      bullishScore: 29,
      changePct: -1.36,
      confidence: 0.63,
      name: '浦发银行（历史）',
      price: 12.42,
      quoteStatus: 'ready',
      riskScore: 72,
      signal: 'high_risk',
      signalStatus: 'ready',
      suggestedPeriod: '方向回填后复核',
      symbol: '600000.SH',
      triggeredRuleCount: 4,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'bearish',
  },
  {
    gateMode: 'shadow',
    riskState: 'ready',
    row: {
      bearishScore: 48,
      bullishScore: 45,
      changePct: -0.42,
      confidence: 0.57,
      name: '万科A（历史）',
      price: 6.98,
      quoteStatus: 'ready',
      riskScore: 66,
      signal: 'high_risk',
      signalStatus: 'ready',
      suggestedPeriod: '方向回填后复核',
      symbol: '000002.SZ',
      triggeredRuleCount: 3,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'watch',
  },
  {
    gateMode: 'none',
    riskState: 'stale',
    row: {
      bearishScore: 36,
      bullishScore: 58,
      changePct: 0.45,
      confidence: 0.65,
      name: '平安银行',
      price: 12.86,
      quoteStatus: 'ready',
      riskScore: null,
      signal: 'bullish',
      signalStatus: 'ready',
      suggestedPeriod: '风险数据更新后复核',
      symbol: '000001.SZ',
      triggeredRuleCount: 3,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'bullish',
  },
  {
    gateMode: 'none',
    riskState: 'insufficient',
    row: {
      bearishScore: 33,
      bullishScore: 49,
      changePct: 0.12,
      confidence: 0.58,
      name: '中芯国际',
      price: 91.42,
      quoteStatus: 'ready',
      riskScore: null,
      signal: 'watch',
      signalStatus: 'ready',
      suggestedPeriod: '历史补齐后复核',
      symbol: '688981.SH',
      triggeredRuleCount: 2,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'watch',
  },
  {
    gateMode: 'none',
    riskState: 'empty',
    row: {
      bearishScore: 21,
      bullishScore: 71,
      changePct: 3.06,
      confidence: 0.67,
      name: '新股示例',
      price: 38.6,
      quoteStatus: 'ready',
      riskScore: null,
      signal: 'bullish',
      signalStatus: 'ready',
      suggestedPeriod: '数据补齐后复核',
      symbol: '688999.SH',
      triggeredRuleCount: 3,
      updatedAt: '2026-07-18 18:10',
    },
    signalDirection: 'bullish',
  },
];

const riskHorizonScore: Record<
  RiskHorizon,
  { level: RiskLevel; score: number; stage: RiskSnapshot['stage'] }
> = {
  '1-5d': { level: 'critical', score: 69.5, stage: 'stampede' },
  '5-20d': { level: 'warning', score: 63.8, stage: 'repricing' },
  '20-60d': { level: 'watch', score: 59.6, stage: 'fragile' },
};

function dashboardRiskEvidence(
  dimension: RiskDimension,
  indicatorCode: string,
  qualityStatus: RiskDataQualityStatus,
  score: null | number,
): RiskSnapshot['evidence'][number] {
  const stale = qualityStatus === 'stale';
  return {
    availableAt: stale
      ? '2026-07-17T16:30:00+08:00'
      : '2026-07-18T16:30:00+08:00',
    details: {
      baselineYears: 5,
      note: stale ? '数据源最近一次成功时间早于当前交易日' : '滚动分位证据',
    },
    dimension,
    indicatorCode,
    observedAt: stale
      ? '2026-07-17T15:00:00+08:00'
      : '2026-07-18T15:00:00+08:00',
    qualityStatus,
    rawValue: score === null ? null : score / 10,
    score,
    source: 'aktools',
  };
}

function buildDashboardRiskSnapshot(
  seed: DashboardRiskSeed,
  horizon: RiskHorizon,
): null | RiskSnapshot {
  if (seed.riskState === 'empty') return null;

  const ready = seed.riskState === 'ready';
  const stale = seed.riskState === 'stale';
  const horizonScore = riskHorizonScore[horizon];
  const qualityStatus: RiskDataQualityStatus = stale
    ? 'stale'
    : ready
      ? 'available'
      : 'insufficient_history';
  const score = ready ? horizonScore.score : null;
  const dimensionScore = score === null ? null : Math.min(100, score + 2);

  return {
    aScore: dimensionScore === null ? null : dimensionScore - 9,
    cScore: dimensionScore === null ? null : dimensionScore + 1,
    calculatedAt: '2026-07-18T18:10:00+08:00',
    completeness: ready ? 0.92 : stale ? 0.84 : 0.58,
    evidence: [
      dashboardRiskEvidence('V', 'V1', qualityStatus, dimensionScore),
      dashboardRiskEvidence('T', 'T2', qualityStatus, dimensionScore),
      dashboardRiskEvidence('S', 'S1', qualityStatus, dimensionScore),
      dashboardRiskEvidence('C', 'C2', qualityStatus, dimensionScore),
      dashboardRiskEvidence('A', 'A2', qualityStatus, dimensionScore),
    ],
    horizon,
    level: ready ? horizonScore.level : null,
    mScore: ready ? 1.05 : null,
    modelVersion: 'risk-v1.0-shadow',
    object: { objectId: seed.row.symbol, objectType: 'stock' },
    riskConfidence: ready ? 0.86 : null,
    sScore: dimensionScore,
    stage: ready ? horizonScore.stage : null,
    tScore: dimensionScore,
    totalScore: score,
    tradeDate: '2026-07-18',
    vScore: dimensionScore,
  };
}

function buildDashboardGateDecision(
  seed: DashboardRiskSeed,
  snapshot: null | RiskSnapshot,
  horizon: RiskHorizon,
): RiskGateDecision | undefined {
  if (
    seed.gateMode === 'none' ||
    !snapshot ||
    snapshot.level === null ||
    snapshot.completeness < 0.8 ||
    snapshot.evidence.some((item) => item.qualityStatus === 'stale') ||
    seed.row.confidence === null
  ) {
    return undefined;
  }
  const signalDirection = seed.signalDirection;
  if (signalDirection === 'bearish' || signalDirection === 'watch') {
    return {
      calculatedAt: snapshot.calculatedAt,
      enforced: false,
      horizon,
      modelVersion: snapshot.modelVersion,
      object: snapshot.object,
      originalConfidence: seed.row.confidence,
      reason: '看跌与观望信号仅附加风险说明，不改写方向或置信度。',
      signalDirection,
      suggestedAction: 'notice',
      suggestedConfidence: seed.row.confidence,
      tradeDate: snapshot.tradeDate,
    };
  }

  const decisionByHorizon = {
    '1-5d': {
      action: 'block' as const,
      confidence: 0,
      reason: '个股完整红色门控，建议拦截看涨信号。',
    },
    '5-20d': {
      action: 'downgrade' as const,
      confidence: Math.max(0, seed.row.confidence - 0.15),
      reason: '中期风险确认，建议降低看涨信号置信度。',
    },
    '20-60d': {
      action: 'notice' as const,
      confidence: seed.row.confidence,
      reason: '长期风险处于关注阶段，仅附加风险说明。',
    },
  }[horizon];
  return {
    calculatedAt: snapshot.calculatedAt,
    enforced: false,
    horizon,
    modelVersion: snapshot.modelVersion,
    object: snapshot.object,
    originalConfidence: seed.row.confidence,
    reason: decisionByHorizon.reason,
    signalDirection,
    suggestedAction: decisionByHorizon.action,
    suggestedConfidence: decisionByHorizon.confidence,
    tradeDate: snapshot.tradeDate,
  };
}

const mockSignalDashboardBase: Omit<
  SignalDashboardOverview,
  'riskHorizon' | 'signals'
> = {
  availableIndustries: ['食品饮料', '电力设备', '银行'],
  marketContext: {
    available: true,
    changePct: 0.68,
    industryStrength: [
      { industry: '电子', status: 'strong', strength: 2.35 },
      { industry: '房地产', status: 'weak', strength: -1.42 },
    ],
    indexName: '沪深300',
    indexValue: 3692.61,
    riskOverview: {
      highRiskCount: 18,
      highRiskRatio: 14.1,
      level: 'medium',
      summary: '高风险信号占比 14.10%，请结合规则解释审慎复核。',
      syncStatus: 'success',
    },
    sentiment: {
      label: '信号情绪（7 日）·偏多',
      score: 62,
      status: 'bullish',
    },
    status: '偏强',
    trend: [
      { label: 'T-4', value: 3650 },
      { label: 'T-3', value: 3668 },
      { label: 'T-2', value: 3642 },
      { label: 'T-1', value: 3680 },
      { label: 'T', value: 3692.61 },
    ],
  },
  metrics: [
    { change: 2, label: '关注股票', tone: 'blue', unit: '只', value: 32 },
    { label: '产生信号', tone: 'cyan', unit: '条', value: 18 },
    { label: '看涨', tone: 'green', unit: '条', value: 6 },
    { label: '看跌', tone: 'red', unit: '条', value: 14 },
    { label: '观望', tone: 'gold', unit: '条', value: 9 },
    { label: '严重风险', tone: 'purple', unit: '个', value: 3 },
    {
      change: 3.2,
      label: '命中率（5日）',
      tone: 'purple',
      unit: '%',
      value: 58.7,
    },
  ],
  pageNum: 1,
  pageSize: 20,
  riskDisclaimer: RISK_DECISION_SUPPORT_NOTICE,
  total: dashboardRiskSeeds.length,
  tradeDate: '2026-07-18',
};

export function selectMockSignalDashboard(
  query: SignalDashboardQuery = {},
): SignalDashboardOverview {
  const riskHorizon = query.riskHorizon ?? '1-5d';
  const signals = dashboardRiskSeeds
    .map((seed): SignalDashboardRow => {
      const riskSnapshot = buildDashboardRiskSnapshot(seed, riskHorizon);
      return {
        ...seed.row,
        riskGateDecision: buildDashboardGateDecision(
          seed,
          riskSnapshot,
          riskHorizon,
        ),
        riskSnapshot,
      };
    })
    .filter((row) => {
      const signalMatched = query.signal
        ? query.signal === 'pending'
          ? row.signalStatus === 'pending'
          : row.signal === query.signal
        : true;
      const symbolMatched = query.symbol
        ? `${row.symbol}${row.name ?? ''}`
            .toLowerCase()
            .includes(query.symbol.toLowerCase())
        : true;
      const minMatched =
        query.confidenceMin === undefined ||
        (row.confidence ?? -1) >= query.confidenceMin;
      const maxMatched =
        query.confidenceMax === undefined ||
        (row.confidence ?? 2) <= query.confidenceMax;
      return signalMatched && symbolMatched && minMatched && maxMatched;
    });
  const pageNum = Math.max(1, query.pageNum ?? 1);
  const pageSize = Math.min(100, Math.max(1, query.pageSize ?? 20));
  const start = (pageNum - 1) * pageSize;
  return {
    ...mockSignalDashboardBase,
    pageNum,
    pageSize,
    riskHorizon,
    signals: signals.slice(start, start + pageSize),
    total: signals.length,
    tradeDate: query.date ?? mockSignalDashboardBase.tradeDate,
  };
}

export const mockSignalDashboard = selectMockSignalDashboard();

export const mockWatchlists: WatchlistPool[] = [
  {
    market: 'A股',
    poolId: 'my-follow',
    poolName: '我的关注',
    stocks: [
      {
        groupName: '白酒',
        industry: '食品饮料',
        market: 'A股',
        name: '贵州茅台',
        selected: true,
        symbol: '600519.SH',
      },
      {
        groupName: '新能源',
        industry: '电力设备',
        market: 'A股',
        name: '宁德时代',
        selected: true,
        symbol: '300750.SZ',
      },
    ],
    total: 2,
  },
  {
    market: 'A股',
    poolId: 'all',
    poolName: '股票池',
    stocks: [
      {
        industry: '食品饮料',
        market: 'A股',
        name: '贵州茅台',
        symbol: '600519.SH',
      },
      {
        industry: '电力设备',
        market: 'A股',
        name: '宁德时代',
        symbol: '300750.SZ',
      },
      {
        industry: '银行',
        market: 'A股',
        name: '招商银行',
        symbol: '600036.SH',
      },
      {
        industry: '家电',
        market: 'A股',
        name: '美的集团',
        symbol: '000333.SZ',
      },
    ],
    total: 4,
  },
];

export const mockAnalysis: StockAnalysis = {
  explanation:
    '短期趋势较强，成交量明显放大，但 RSI 进入过热区间且新闻情绪偏负面，建议作为观察信号结合仓位和市场环境复核。',
  factors: {
    fundamental_status: 'good',
    market_status: 'weak',
    news_sentiment: 'negative',
    short_term_trend: 'strong_up',
    technical_status: 'overbought',
    volume_status: 'abnormal_high',
  },
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
  signal: 'watch',
  symbol: 'AAPL',
  triggeredRules: [
    'R_TREND_BREAKOUT_001',
    'R_RISK_OVERBOUGHT_002',
    'R_SENTIMENT_NEGATIVE_003',
  ],
};

export const mockStockResearchDetail: StockResearchDetail = {
  confidence: 72,
  explanation: mockAnalysis.explanation,
  factors: [
    {
      description: '价格突破 MA20 且量能放大',
      factor: 'MA20 突破',
      status: '看涨',
      strength: 80,
      value: '突破',
    },
    {
      description: 'RSI 进入偏高区间，需要观察回调风险',
      factor: 'RSI(14)',
      status: '偏高',
      strength: 72,
      value: '72.3',
    },
  ],
  history: [
    {
      actualReturn: 2.34,
      confidence: 68,
      date: '2026-06-13',
      direction: '上涨',
      hitStatus: '命中',
      signal: 'bullish',
      triggeredRules: ['R_TREND_BREAKOUT_001'],
    },
  ],
  industry: '科技',
  market: '美股',
  name: 'Apple Inc.',
  priceSeries: [
    { close: 186.2, date: '2026-06-16', volume: 5200 },
    { close: 189.3, date: '2026-06-17', volume: 6100 },
    { close: 192.2, date: '2026-06-20', volume: 7200 },
  ],
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
  riskScore: 18,
  ruleChain: [
    {
      condition: 'close > ma(close, 20)',
      contribution: 25,
      ruleCode: 'R_TREND_BREAKOUT_001',
      ruleName: '趋势突破策略',
    },
  ],
  signal: 'bullish',
  symbol: 'AAPL',
  tradeDate: '2026-06-20',
};

export const mockRules: RuleDefinition[] = [
  {
    createdBy: 'system',
    priority: 80,
    ruleCode: 'R_TREND_BREAKOUT_001',
    ruleContent:
      'short_term_trend = strong_up AND volume_status = abnormal_high',
    ruleFormat: 'json',
    ruleName: '强势突破看涨',
    ruleType: 'trend',
    status: 'active',
    updatedTime: '2026-06-20 16:40',
    version: 'v1.3',
  },
  {
    createdBy: 'system',
    priority: 95,
    ruleCode: 'R_RISK_OVERBOUGHT_002',
    ruleContent: 'technical_status = overbought AND risk_score > 70',
    ruleFormat: 'json',
    ruleName: '高位过热风险',
    ruleType: 'risk',
    status: 'active',
    updatedTime: '2026-06-20 16:42',
    version: 'v1.1',
  },
  {
    createdBy: 'ai-review',
    priority: 60,
    ruleCode: 'R_SENTIMENT_NEGATIVE_003',
    ruleContent: 'news_sentiment = negative AND market_status = weak',
    ruleFormat: 'drools',
    ruleName: '负面舆情降级',
    ruleType: 'sentiment',
    status: 'paper_trade',
    updatedTime: '2026-06-20 18:10',
    version: 'v0.8',
  },
];

export const mockCandidates: CandidateRule[] = [
  {
    backtestResult: '待回测',
    candidateCode: 'CR_20260620_001',
    changeType: 'add_filter',
    originalContent:
      'short_term_trend = strong_up AND volume_status = abnormal_high',
    proposedContent:
      'short_term_trend = strong_up AND volume_status = abnormal_high AND market_status != weak',
    reason: '减少弱势市场下的假突破误判',
    source: 'AI',
    status: 'candidate',
    targetRuleCode: 'R_TREND_BREAKOUT_001',
    updatedTime: '2026-06-20 19:15',
  },
  {
    backtestResult: '5 日胜率 54%，最大回撤 -8.1%',
    candidateCode: 'CR_20260620_002',
    changeType: 'adjust_threshold',
    originalContent: 'risk_score > 70',
    proposedContent: 'risk_score > 65 AND confidence < 0.7',
    reason: '提前识别低置信度高风险样本',
    source: 'AI',
    status: 'backtesting',
    targetRuleCode: 'R_RISK_OVERBOUGHT_002',
    updatedTime: '2026-06-20 20:00',
  },
];

export const mockRuleGovernanceOverview: RuleGovernanceOverview = {
  metrics: [
    { label: '总规则数', tone: 'blue', unit: '条', value: 86 },
    { label: '已上线', tone: 'green', unit: '条', value: 42 },
    { label: '候选规则', tone: 'cyan', unit: '条', value: 18 },
  ],
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
  rules: mockRules.map((rule, index) => ({
    avgReturn: 0.72 - index * 0.12,
    maxDrawdown: -6.21 - index,
    ruleCode: rule.ruleCode,
    ruleName: rule.ruleName,
    ruleType: rule.ruleType,
    status: rule.status,
    triggerCount30d: 1248 - index * 120,
    updatedAt: rule.updatedTime,
    version: rule.version,
    winRate5d: 58.2 - index * 2,
  })),
};

export const mockBacktest: BacktestResult = {
  avgReturn: 0.018,
  conclusion: '规则在牛市和震荡市表现较好，在弱势市场下误判较多。',
  endDate: '2026-06-20',
  maxDrawdown: -0.092,
  objectCode: 'R_TREND_BREAKOUT_001',
  objectType: 'rule',
  sharpeRatio: 1.21,
  startDate: '2023-01-01',
  triggerCount: 842,
  winRate: 0.57,
};

export const mockBacktestReportOverview: BacktestReportOverview = {
  comparison: [
    {
      candidateValue: 59.1,
      currentValue: 57.2,
      metric: '胜率',
      winner: 'candidate',
    },
  ],
  cumulativeReturns: [
    { date: '2024-01-01', value: 0 },
    { date: '2024-06-01', value: 12.4 },
    { date: '2025-01-01', value: 28.7 },
    { date: '2026-06-20', value: 38.7 },
  ],
  failureSamples: [
    {
      actualReturn: -8.21,
      date: '2026-05-14',
      reasonCategory: '突发利空',
      relatedRule: 'R_TREND_BREAKOUT_001',
      signal: '买入',
      symbol: '600519.SH',
    },
  ],
  metrics: [
    { label: '触发次数', tone: 'blue', unit: '次', value: 842 },
    { label: '5日胜率', tone: 'green', unit: '%', value: 57.2 },
    { label: '平均收益', tone: 'purple', unit: '%', value: 1.8 },
    { label: '最大回撤', tone: 'red', unit: '%', value: -9.2 },
  ],
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
};

export const mockAiReview: AiReviewReport = {
  diagnosis:
    '本次误判主要原因是系统忽略了大盘和行业整体弱势，虽然个股出现放量突破，但属于弱势环境下的假突破。',
  modelName: 'mock-reviewer',
  relatedRules: ['R_TREND_BREAKOUT_001'],
  reviewDate: '2026-06-20',
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
  suggestions: [
    {
      condition: 'market_status != weak AND industry_status != weak',
      needBacktest: true,
      reason: '避免在弱势市场中误判假突破',
      risk: '可能减少部分强势个股机会',
      ruleId: 'R_TREND_BREAKOUT_001',
      type: 'add_filter',
    },
  ],
};

export const mockAiReviewOverview: AiReviewOverview = {
  candidateSuggestion: {
    actionRequired: '需回测',
    candidateCode: 'CR_20260620_001',
    oldCondition: 'close > highest(close, 20)',
    priority: 'high',
    proposedCondition:
      'close > highest(close, 20) * 1.002 AND volume > ma(volume, 20) * 1.5',
    targetRuleCode: 'R_TREND_BREAKOUT_001',
  },
  errorClusters: [
    { count: 16, ratio: 41, reasonCategory: '弱势市场假突破' },
    { count: 9, ratio: 23.1, reasonCategory: '新闻突发未纳入' },
  ],
  metrics: [
    { label: '今日预测数', tone: 'blue', unit: '条', value: 128 },
    { label: '已验证', tone: 'green', unit: '条', value: 110 },
    { label: '未命中', tone: 'red', unit: '条', value: 39 },
  ],
  misjudgements: [
    {
      actualReturn: -2.31,
      predictedSignal: 'bullish',
      predictionDate: '2026-06-20',
      reasonCategory: '弱势市场假突破',
      sampleId: 'signal-600519',
      status: 'analysis',
      symbol: '600519.SH',
      triggeredRule: 'R_TREND_BREAKOUT_001',
    },
  ],
  reviewDate: '2026-06-20',
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
};

export const mockMarketDataSyncRuns: MarketDataSyncRun[] = [
  {
    dataSource: 'mock-provider',
    durationMs: 1320,
    endDate: '2026-06-20',
    failed: 0,
    finishedAt: '2026-06-20 15:01:22',
    inserted: 28,
    runId: 2_026_062_001,
    scanned: 60,
    skipped: 2,
    startedAt: '2026-06-20 15:00:00',
    startDate: '2026-06-01',
    status: 'success',
    syncType: 'daily_quote',
    targetSymbol: 'AAPL',
    triggerBy: 'operator',
    triggerType: 'manual',
    updated: 30,
  },
];

export const mockWorkflowDependencies: DailyWorkflowDependency[] = [
  {
    mergeRisk: '行情数据缺口会导致因子和信号质量下降',
    moduleCode: 'market-data',
    moduleName: '行情采集',
    requiredCapability: '同步股票基础信息、交易日历和日 K 行情',
  },
  {
    mergeRisk: '回测样本不足会降低候选规则审核可信度',
    moduleCode: 'backtest',
    moduleName: '回测验证',
    requiredCapability: '对规则和候选规则输出胜率、回撤等辅助指标',
  },
  {
    mergeRisk: 'AI 复盘只能输出候选改进建议，不能直接上线规则',
    moduleCode: 'ai-review',
    moduleName: 'AI 复盘',
    requiredCapability: '解释误判并生成候选规则建议',
  },
];

export const mockDailyWorkflowRun: DailyWorkflowRunResult = {
  dryRun: true,
  finishedAt: '2026-06-20 17:04:30',
  integrationDependencies: mockWorkflowDependencies,
  riskDisclaimer: STOCK_RISK_DISCLAIMER,
  runId: 'WF_20260620_170000',
  startedAt: '2026-06-20 17:00:00',
  status: 'success',
  steps: [
    {
      message: '行情样本已同步到本地库',
      status: 'success',
      stepCode: 'market_data_sync',
      stepName: '行情采集',
    },
    {
      message: '技术因子已完成计算',
      status: 'success',
      stepCode: 'factor_calculation',
      stepName: '因子计算',
    },
    {
      message: '规则推理完成，仅输出辅助决策信号',
      status: 'success',
      stepCode: 'rule_signal_generation',
      stepName: '信号生成',
    },
    {
      message: '候选规则进入回测队列，等待人工复核',
      status: 'pending',
      stepCode: 'candidate_rule_backtest',
      stepName: '候选回测',
    },
  ],
  symbols: ['AAPL', 'MSFT'],
  tradeDate: '2026-06-20',
  triggerType: 'manual',
};

export const mockRunCenterOverview: RunCenterOverview = {
  metrics: [
    { label: '工作流运行', tone: 'blue', unit: '次', value: 1 },
    { label: '同步扫描', tone: 'green', unit: '条', value: 60 },
    { label: '失败数', tone: 'red', unit: '条', value: 0 },
  ],
  serviceStatus: 'success',
  steps: mockDailyWorkflowRun.steps.map((step) => ({
    message: step.message,
    status: step.status,
    stepCode: step.stepCode,
    stepName: step.stepName ?? step.stepCode,
  })),
  tradeDate: '2026-06-20',
};

export const mockRuleVersions: RuleVersion[] = [
  {
    approvalStatus: 'published',
    changeReason: '上线弱势市场过滤条件，降低假突破样本',
    createdBy: 'operator',
    createdTime: '2026-06-20 18:20',
    id: 101,
    publishedTime: '2026-06-20 19:00',
    ruleContent:
      'short_term_trend = strong_up AND volume_status = abnormal_high AND market_status != weak',
    ruleId: 1,
    source: 'candidate_rule',
    versionNo: 'v1.3',
  },
  {
    approvalStatus: 'approved',
    changeReason: 'AI 复盘建议，等待发布窗口',
    createdBy: 'ai-review',
    createdTime: '2026-06-19 20:10',
    id: 100,
    ruleContent:
      'short_term_trend = strong_up AND volume_status = abnormal_high',
    ruleId: 1,
    source: 'manual',
    versionNo: 'v1.2',
  },
];
