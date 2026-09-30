import type { RiskGateDecision, RiskHorizon, RiskSnapshot } from './risk/types';

export type SignalType = 'bearish' | 'bullish' | 'high_risk' | 'watch';
export type DashboardSignalFilter = 'pending' | SignalType;
export type ResearchSignalStatus = 'pending' | 'ready';

export type RuleStatus =
  | 'active'
  | 'approved'
  | 'archived'
  | 'backtesting'
  | 'candidate'
  | 'disabled'
  | 'draft'
  | 'paper_trade';

export type RuleFormat = 'drools' | 'json';

export type BacktestObjectType = 'candidate_rule' | 'rule' | 'rule_group' | 'strategy';

export type CandidateRuleLifecycleStatus =
  | 'approved'
  | 'backtested'
  | 'disabled'
  | 'generated'
  | 'pending_review'
  | 'published'
  | 'rejected'
  | 'validated';

export type CandidateRuleStatus = CandidateRuleLifecycleStatus | RuleStatus;

export type RunStatus =
  | 'failed'
  | 'partial'
  | 'pending'
  | 'running'
  | 'skipped'
  | 'success';

export type RuleVersionApprovalStatus =
  | 'approved'
  | 'pending'
  | 'published'
  | 'rejected'
  | 'rolled_back';

export type MarketDataSyncType =
  | 'daily_quote'
  | 'stock_list'
  | 'trade_calendar';

export type WorkflowRunTriggerType = 'manual' | 'scheduled' | string;

export type WorkflowStepCode =
  | 'ai_review'
  | 'candidate_rule_backtest'
  | 'factor_calculation'
  | 'market_data_sync'
  | 'prediction_validation'
  | 'risk_warning'
  | 'rule_inference'
  | 'rule_signal_generation';

export interface StockSignalQuery {
  date?: string;
  pageNum?: number;
  pageSize?: number;
  signal?: SignalType;
  symbol?: string;
}

export interface StockSignalItem {
  bearishScore?: number;
  bullishScore: number;
  confidence: number;
  currentPrice?: number;
  name?: string;
  riskDisclaimer?: string;
  riskScore: number;
  signal: SignalType;
  signalLevel?: string;
  suggestedPeriod?: string;
  symbol: string;
  triggeredRuleCount?: number;
  updatedAt?: string;
}

export interface MetricCard {
  change?: number;
  label: string;
  tone?: string;
  unit?: string;
  value: number;
}

export interface BacktestMetricCard extends Omit<MetricCard, 'value'> {
  value: null | number;
}

export interface DashboardMetricCard extends Omit<MetricCard, 'value'> {
  value: null | number;
}

export interface SparkPoint {
  label: string;
  value: number;
}

export interface SignalDashboardRow {
  bearishScore: null | number;
  bullishScore: null | number;
  changePct?: number;
  confidence: null | number;
  generationType?: 'backfill' | 'regular' | null;
  name?: string;
  price?: number;
  quoteDate?: null | string;
  quoteStatus: 'pending' | 'ready';
  riskGateDecision?: RiskGateDecision;
  riskSnapshot: null | RiskSnapshot;
  riskScore: null | number;
  signal: null | SignalType;
  signalDate?: null | string;
  signalFreshness?: 'current' | 'historical' | 'missing';
  signalGeneratedAt?: null | string;
  signalStatus: 'pending' | 'ready';
  suggestedPeriod?: string;
  symbol: string;
  triggeredRuleCount: number;
  updatedAt?: string;
}

export interface IndustryStrength {
  industry: string;
  status: string;
  strength: number;
}

export interface SignalSentiment {
  label: string;
  score: null | number;
  status: string;
}

export interface RiskOverview {
  highRiskCount: number;
  highRiskRatio: null | number;
  level?: null | string;
  summary: string;
  syncStatus: string;
}

export interface MarketContext {
  available: boolean;
  changePct: null | number;
  indexName: null | string;
  indexValue: null | number;
  industryStrength: IndustryStrength[];
  riskOverview: RiskOverview;
  sentiment: SignalSentiment;
  status: string;
  trend: SparkPoint[];
}

export interface SignalDashboardOverview {
  availableIndustries: string[];
  dataUpdatedAt?: null | string;
  latestSignalDate?: null | string;
  marketContext: MarketContext;
  metrics: DashboardMetricCard[];
  pageNum: number;
  pageSize: number;
  riskDisclaimer: string;
  riskHorizon: RiskHorizon;
  signals: SignalDashboardRow[];
  signalUpdatedAt?: null | string;
  quoteUpdatedAt?: null | string;
  total: number;
  tradeDate?: string;
}

export interface SignalDashboardQuery {
  confidenceMax?: number;
  confidenceMin?: number;
  date?: string;
  industry?: string;
  market?: string;
  pageNum?: number;
  pageSize?: number;
  poolCode?: string;
  riskHorizon?: RiskHorizon;
  signal?: DashboardSignalFilter;
  sortField?: string;
  sortOrder?: 'asc' | 'desc';
  symbol?: string;
}

export interface WatchlistStock {
  groupName?: string;
  industry?: string;
  market?: string;
  name?: string;
  selected?: boolean;
  symbol: string;
}

export interface WatchlistPool {
  market: string;
  poolId: string;
  poolName: string;
  stocks: WatchlistStock[];
  total: number;
}

export interface WatchlistStockMutationRequest {
  groupName?: string;
  symbol: string;
}

export interface WatchlistCandidate extends WatchlistStock {
  exchange?: string;
  inPool: boolean;
}

export interface WatchlistCandidateQuery {
  keyword?: string;
  market?: string;
  pageNum?: number;
  pageSize?: number;
}

export interface WatchlistBatchMutationRequest {
  groupName?: string;
  symbols: string[];
}

export interface WatchlistBatchMutationResult {
  addedSymbols: string[];
  failedSymbols: string[];
  poolCode: string;
  skippedSymbols: string[];
}

export interface WatchlistMutationRequest {
  market: string;
  poolName: string;
}

export interface PricePoint {
  close: number;
  date: string;
  volume?: number;
}

export interface FactorState {
  description?: string;
  factor: string;
  status: string;
  strength: number;
  value: string;
}

export interface RuleContribution {
  condition?: string;
  contribution?: null | number;
  ruleCode: string;
  ruleName: string;
}

export interface SignalTraceCondition {
  actual: unknown;
  expected: unknown;
  field: string;
  operator: string;
  status: 'INVALID' | 'MATCHED' | 'MISSING' | 'NOT_MATCHED';
}

export interface SignalTraceRuleEvaluation {
  bearishDelta: number;
  bullishDelta: number;
  code: string;
  conditions: SignalTraceCondition[];
  evidenceStatus: 'FULL' | 'PARTIAL' | 'UNAVAILABLE';
  explanation: string;
  format: string;
  name: string;
  priority: number;
  riskDelta: number;
  status: 'MATCHED' | 'NOT_MATCHED';
  version: string;
}

export interface SignalTraceScores {
  bearish: number;
  bullish: number;
  risk: number;
}

export interface SignalTraceDecision {
  confidence: number;
  conflict: boolean;
  direction: SignalType;
  effectiveScores: SignalTraceScores;
  level: string;
  rawScores: SignalTraceScores;
  reason: string;
  riskOverride: boolean;
  signal: SignalType;
  signedRuleTotals?: SignalTraceScores;
  sourceClamps?: {
    format: 'drools' | 'json';
    nonNegativeScores: SignalTraceScores;
    signedScores: SignalTraceScores;
  }[];
  thresholds: {
    bearish: number;
    bullish: number;
    highRisk: number;
    strongBullish: number;
  };
}

export interface SignalTrace {
  decision: SignalTraceDecision;
  factorDate?: null | string;
  factorSnapshot: Record<string, unknown>;
  ruleEvaluations: SignalTraceRuleEvaluation[];
}

export interface PredictionRecord {
  actualReturn?: number;
  confidence: null | number;
  date: string;
  direction: string;
  hitStatus: string;
  signal: SignalType;
  triggeredRules: string[];
}

export interface ResearchSignalVersion {
  availableAt: null | string;
  versionNo: number;
}

export interface StockResearchDetail {
  confidence: null | number;
  currentVersionNo: null | number;
  explanation: string;
  factorDate?: null | string;
  factors: FactorState[];
  history: PredictionRecord[];
  industry?: string;
  market?: string;
  name?: string;
  priceSeries: PricePoint[];
  quoteDate?: null | string;
  riskDisclaimer: string;
  riskScore: null | number;
  ruleChain: RuleContribution[];
  signal: SignalType;
  signalDate?: null | string;
  signalStatus: ResearchSignalStatus;
  symbol: string;
  trace?: null | SignalTrace;
  traceStatus?: 'complete' | 'legacy' | 'partial';
  tradeDate?: string;
  versions: ResearchSignalVersion[];
}

export interface RuleSummary {
  avgReturn: number;
  description?: string;
  enabled?: boolean | null;
  maxDrawdown: number;
  priority?: number;
  ruleCode: string;
  ruleFormat?: RuleFormat | string;
  ruleName: string;
  ruleType: string;
  productionExecutable?: boolean;
  status: RuleStatus;
  triggerCount30d: number;
  updatedAt?: string;
  version: string;
  winRate5d: number;
}

export interface VersionDiff {
  currentContent: string;
  highlights: string[];
  proposedContent: string;
}

export interface RuleGovernanceDetail {
  candidateDiff?: VersionDiff;
  description?: string;
  enabled?: boolean | null;
  expression: string;
  performance: BacktestMetricCard[];
  priority?: number;
  relatedFactors: string[];
  ruleCode: string;
  ruleFormat?: RuleFormat | string;
  ruleName: string;
  ruleType: string;
  productionExecutable?: boolean;
  source?: string;
  status: RuleStatus | string;
  version: string;
}

export interface RuleGovernanceOverview {
  metrics: MetricCard[];
  riskDisclaimer: string;
  rules: RuleSummary[];
}

export interface SeriesPoint {
  date: string;
  value: number;
}

export interface ComparisonMetric {
  candidateValue: number;
  currentValue: number;
  metric: string;
  winner: string;
}

export interface BacktestFailureSample {
  actualReturn: number;
  date: string;
  reasonCategory: string;
  relatedRule: string;
  signal: string;
  symbol: string;
}

export interface BacktestReportDetail {
  cumulativeReturns: SeriesPoint[];
  createdTime?: string;
  equityCurve?: SeriesPoint[];
  endDate?: string;
  failureSamples: BacktestFailureSample[];
  metrics: BacktestMetricCard[];
  objectCode: string;
  objectType: BacktestObjectType | string;
  reportId: string;
  startDate?: string;
  status?: RunStatus | string;
  holdingPeriod?: number;
  stockPoolType?: BacktestRequest['stockPoolType'] | null;
  stockPoolCode?: string | null;
  symbols?: string[];
  sampleCount?: number;
  evaluatedCount?: number;
  unevaluableCount?: number;
  resultJson?: string;
}

export interface BacktestReportHistoryItem {
  avgReturn?: number | null;
  createdTime?: string | null;
  endDate?: string | null;
  evaluatedCount?: number | null;
  holdingPeriod?: number | null;
  maxDrawdown?: number | null;
  objectCode: string;
  objectType: BacktestObjectType | string;
  reportId: string;
  sampleCount?: number | null;
  startDate?: string | null;
  status?: RunStatus | string | null;
  stockPoolCode?: string | null;
  stockPoolType?: BacktestRequest['stockPoolType'] | null;
  symbols?: string[];
  totalReturn?: number | null;
  triggerCount?: number | null;
  unevaluableCount?: number | null;
  winRate?: number | null;
}

export interface BacktestReportOverview {
  comparison: ComparisonMetric[];
  /**
   * Legacy aggregate series. New reports should use equityCurve, which is
   * built from the selected run's historical samples.
   */
  cumulativeReturns: SeriesPoint[];
  equityCurve?: SeriesPoint[];
  failureSamples: BacktestFailureSample[];
  metrics: BacktestMetricCard[];
  reportId?: string;
  riskDisclaimer: string;
  sampleCount?: number;
  evaluatedCount?: number;
  unevaluableCount?: number;
  status?: RunStatus | string;
  resultJson?: string;
}

export interface BacktestCombinationScores {
  bearish: number;
  bullish: number;
  risk: number;
}

export interface BacktestCombinationGroupSnapshot {
  aggregation: 'AND' | 'OR' | 'WEIGHTED';
  groupCode: string;
  groupName: string;
  members: {
    required: boolean;
    ruleCode: string;
    ruleVersionNo?: string;
    weight: number;
  }[];
  minMatchedRules: number;
  version: string;
}

export interface BacktestCombinationSnapshot {
  bearishThreshold?: null | number;
  bullishThreshold?: null | number;
  groups: {
    group: BacktestCombinationGroupSnapshot;
    groupCode: string;
    groupVersion: string;
    required: boolean;
    weight: number;
  }[];
  riskThreshold?: null | number;
  strategyCode: string;
  strategyName: string;
  version: string;
}

export interface BacktestCombinationTrace {
  groupContributions: {
    aggregation: string;
    countedRules: string[];
    eligible: boolean;
    groupCode: string;
    groupVersion: string;
    matchedCount: number;
    memberCount: number;
    required: boolean;
    weightedScores: BacktestCombinationScores;
  }[];
  requiredGroupGatePassed: boolean;
  ruleContributions: {
    actualRuleVersion: string;
    directionGroupCode: null | string;
    directionWeight: number;
    matchStatus: string;
    originalScores: BacktestCombinationScores;
    riskGroupCode: null | string;
    riskWeight: number;
    ruleCode: string;
    weightedScores: BacktestCombinationScores;
  }[];
  unmetRequiredGroups: string[];
}

export interface BacktestCombinationContribution {
  bearishScore: number;
  bullishScore: number;
  date: string;
  riskScore: number;
  signal: SignalType;
  symbol: string;
  trace: BacktestCombinationTrace;
}

export interface BacktestReportResultPayload {
  directionalCount?: number;
  emptyReason?: string;
  emptyReasonCode?: string;
  equityCurve?: SeriesPoint[];
  errorSummary?: string;
  evaluationBasis?: 'combination_signal' | 'rule_direction' | 'stored_signal';
  combinationContributions?: BacktestCombinationContribution[];
  combinationSnapshot?: BacktestCombinationSnapshot;
  executedRuleVersions?: Record<string, string>;
  evaluatedCount?: number;
  signalCount?: number;
  skippedCount?: number;
  statisticsVersion?: number;
  status?: RunStatus | string;
  triggerCount?: number;
  undirectedCount?: number;
  unevaluableCount?: number;
  watchCount?: number;
}

export interface ErrorCluster {
  count: number;
  ratio: number;
  reasonCategory: string;
}

export interface MisjudgementSample {
  actualReturn: number;
  predictedSignal: SignalType;
  predictionDate: string;
  reasonCategory: string;
  sampleId: string;
  status: string;
  symbol: string;
  triggeredRule: string;
}

export interface CandidateRuleSuggestion {
  actionRequired: string;
  candidateCode: string;
  oldCondition: string;
  priority: string;
  proposedCondition: string;
  targetRuleCode: string;
}

export interface AiReviewOverview {
  candidateSuggestion?: CandidateRuleSuggestion;
  errorClusters: ErrorCluster[];
  metrics: MetricCard[];
  misjudgements: MisjudgementSample[];
  reviewDate?: string;
  riskDisclaimer: string;
}

export interface RunStep {
  finishedAt?: string;
  message?: string;
  startedAt?: string;
  status: RunStatus | string;
  stepCode: string;
  stepName: string;
}

export interface RunCenterOverview {
  metrics: MetricCard[];
  runId?: string;
  serviceStatus: string;
  steps: RunStep[];
  tradeDate?: string;
}

export interface StockAnalysis {
  explanation: string;
  factors: Record<string, unknown>;
  riskDisclaimer?: string;
  signal: SignalType;
  symbol: string;
  triggeredRules: string[];
}

export interface RuleDefinition {
  createdBy?: string;
  currentVersionId?: number;
  currentVersionNo?: string;
  description?: string;
  enabled?: boolean;
  priority: number;
  ruleCode: string;
  ruleContent: string;
  ruleFormat: RuleFormat;
  ruleName: string;
  ruleType: string;
  status: RuleStatus;
  updatedBy?: string;
  updatedTime?: string;
  version: string;
}

export interface RuleDefinitionUpsert {
  description?: string;
  priority: number;
  ruleCode: string;
  ruleContent: string;
  ruleFormat: RuleFormat;
  ruleName: string;
  ruleType: string;
  status: RuleStatus;
  version: string;
}

export interface CandidateRule {
  approvalStatus?: RuleVersionApprovalStatus;
  backtestStatus?: RunStatus;
  backtestResult?: string;
  candidateCode: string;
  changeType: string;
  latestBacktestReportId?: number;
  originalContent: string;
  proposedContent: string;
  reason: string;
  rejectReason?: string;
  source: string;
  sourceReviewId?: number;
  status: CandidateRuleStatus;
  targetRuleCode: string;
  updatedTime?: string;
}

export interface CandidateRuleStatusUpdate {
  status: CandidateRuleStatus;
}

export interface CandidateRulePublishRequest {
  operator?: string;
  reason: string;
  ruleName?: string;
  description?: string;
  ruleType?: string;
}

export interface BacktestRequest {
  endDate: string;
  holdingPeriod: number;
  objectCode: string;
  objectType: BacktestObjectType;
  startDate: string;
  /**
   * Optional scope for the historical replay.  The backend accepts these
   * fields to avoid forcing every backtest through the whole market.
   */
  stockPoolCode?: string;
  stockPoolType?: 'custom' | 'market' | 'watchlist';
  symbols?: string[];
}

export interface BacktestResult {
  avgReturn: null | number;
  avgHoldingReturn?: null | number;
  candidateRuleId?: number;
  conclusion?: string;
  endDate: string;
  feeRate?: number;
  holdingPeriod?: number;
  id?: number;
  maxDrawdown: null | number;
  objectCode: string;
  objectType: BacktestObjectType;
  profitLossRatio?: null | number;
  ruleId?: number;
  resultJson?: string;
  sharpeRatio: null | number;
  slippageRate?: number;
  startDate: string;
  status?: RunStatus;
  symbol?: string;
  totalReturn?: null | number;
  triggerCount: number;
  winRate: null | number;
}

export interface TradeCalendar {
  dataSource?: string;
  isOpen?: boolean;
  market: string;
  nextTradeDate?: string;
  open?: boolean;
  preTradeDate?: string;
  syncTime?: string;
  tradeDate: string;
}

export interface MarketDataSyncRequest {
  endDate?: string;
  targetSymbol?: string;
  triggerBy?: string;
  triggerType?: string;
  startDate?: string;
}

export interface MarketDataSyncRun {
  dataSource?: string;
  durationMs?: number;
  endDate?: string;
  errors?: string[];
  failed: number;
  finishedAt?: string;
  id?: number;
  inserted: number;
  requestParams?: string;
  runId?: number;
  scanned: number;
  skipped: number;
  startedAt?: string;
  startDate?: string;
  status: RunStatus;
  syncType: MarketDataSyncType;
  targetSymbol?: string;
  triggerBy?: string;
  triggerType?: string;
  updated: number;
}

export interface MarketDataImportBatchSummary {
  acceptedRows?: unknown[];
  insertedRows?: number;
  rejectedCount?: number;
  rejectedRows?: Array<{
    reason?: string;
    rowNumber?: number;
    symbol?: string;
  }>;
  totalRows?: number;
  updatedRows?: number;
}

export interface MarketDataImportMockResult {
  dailyQuotes?: MarketDataImportBatchSummary;
  stocks?: MarketDataImportBatchSummary;
}

export interface RuleVersion {
  approvalStatus: RuleVersionApprovalStatus;
  changeReason?: string;
  createdBy?: string;
  createdTime?: string;
  id: number;
  publishedTime?: string;
  ruleContent: string;
  ruleId: number;
  source?: string;
  versionNo: string;
}

export interface RuleVersionRollbackRequest {
  operator?: string;
  reason: string;
}

export interface RuleOperationLog {
  afterStatus?: string;
  beforeStatus?: string;
  createdTime?: string;
  id: number;
  operation: string;
  operator?: string;
  reason?: string;
  targetId: string;
  targetType: string;
}

export interface WorkflowRun {
  bizDate: string;
  dryRun: boolean;
  errorMessage?: string;
  finishedAt?: string;
  id: number;
  requestParams?: string;
  startedAt?: string;
  status: RunStatus;
  summary?: string;
  triggerBy?: string;
  triggerType?: string;
}

export interface DailyWorkflowTriggerRequest {
  dryRun?: boolean;
  symbols?: string[];
  tradeDate?: string;
}

export interface DailyWorkflowDependency {
  mergeRisk?: string;
  moduleCode: string;
  moduleName: string;
  requiredCapability: string;
}

export interface DailyWorkflowStepResult {
  details?: Record<string, unknown>;
  finishedAt?: string;
  message?: string;
  startedAt?: string;
  status: RunStatus;
  stepCode: string | WorkflowStepCode;
  stepName?: string;
}

export interface DailyWorkflowRunResult {
  dryRun?: boolean;
  finishedAt?: string;
  integrationDependencies?: DailyWorkflowDependency[];
  riskDisclaimer?: string;
  runId: string;
  startedAt?: string;
  status: RunStatus;
  steps: DailyWorkflowStepResult[];
  symbols?: string[];
  tradeDate?: string;
  triggerType?: WorkflowRunTriggerType;
}

export interface WorkflowStepRun {
  durationMs?: number;
  errorMessage?: string;
  finishedAt?: string;
  id: number;
  inputParams?: string;
  outputSummary?: string;
  startedAt?: string;
  status: RunStatus;
  stepCode: WorkflowStepCode;
  stepOrder: number;
  workflowRunId: number;
}

export interface AiReviewRequest {
  date: string;
  mode: 'daily' | 'rule' | 'symbol';
  symbol?: string;
}

export interface AiSuggestion {
  condition: string;
  needBacktest: boolean;
  reason: string;
  risk: string;
  ruleId: string;
  type: string;
}

export interface AiReviewReport {
  diagnosis: string;
  modelName?: string;
  relatedRules: string[];
  riskDisclaimer?: string;
  reviewDate: string;
  suggestions: AiSuggestion[];
  symbol?: string;
}
