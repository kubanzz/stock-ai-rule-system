import type { RiskGateDecision, RiskHorizon, RiskSnapshot } from './risk/types';

export type SignalType = 'bearish' | 'bullish' | 'high_risk' | 'watch';
export type DashboardSignalFilter = 'pending' | SignalType;

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

export type BacktestObjectType = 'candidate_rule' | 'rule';

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
  name?: string;
  price?: number;
  quoteStatus: 'pending' | 'ready';
  riskGateDecision?: RiskGateDecision;
  riskSnapshot: null | RiskSnapshot;
  riskScore: null | number;
  signal: null | SignalType;
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
  marketContext: MarketContext;
  metrics: DashboardMetricCard[];
  pageNum: number;
  pageSize: number;
  riskDisclaimer: string;
  riskHorizon: RiskHorizon;
  signals: SignalDashboardRow[];
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
  contribution: number;
  ruleCode: string;
  ruleName: string;
}

export interface PredictionRecord {
  actualReturn?: number;
  confidence: number;
  date: string;
  direction: string;
  hitStatus: string;
  signal: SignalType;
  triggeredRules: string[];
}

export interface StockResearchDetail {
  confidence: number;
  explanation: string;
  factors: FactorState[];
  history: PredictionRecord[];
  industry?: string;
  market?: string;
  name?: string;
  priceSeries: PricePoint[];
  riskDisclaimer: string;
  riskScore: number;
  ruleChain: RuleContribution[];
  signal: SignalType;
  symbol: string;
  tradeDate?: string;
}

export interface RuleSummary {
  avgReturn: number;
  maxDrawdown: number;
  ruleCode: string;
  ruleName: string;
  ruleType: string;
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
  expression: string;
  performance: MetricCard[];
  relatedFactors: string[];
  ruleCode: string;
  ruleName: string;
  ruleType: string;
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
  endDate?: string;
  failureSamples: BacktestFailureSample[];
  metrics: MetricCard[];
  objectCode: string;
  objectType: BacktestObjectType | string;
  reportId: string;
  startDate?: string;
}

export interface BacktestReportOverview {
  comparison: ComparisonMetric[];
  cumulativeReturns: SeriesPoint[];
  failureSamples: BacktestFailureSample[];
  metrics: MetricCard[];
  riskDisclaimer: string;
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
}

export interface BacktestRequest {
  endDate: string;
  holdingPeriod: number;
  objectCode: string;
  objectType: BacktestObjectType;
  startDate: string;
}

export interface BacktestResult {
  avgReturn: number;
  avgHoldingReturn?: number;
  candidateRuleId?: number;
  conclusion?: string;
  endDate: string;
  feeRate?: number;
  holdingPeriod?: number;
  maxDrawdown: number;
  objectCode: string;
  objectType: BacktestObjectType;
  profitLossRatio?: number;
  ruleId?: number;
  sharpeRatio: number;
  slippageRate?: number;
  startDate: string;
  status?: RunStatus;
  symbol?: string;
  totalReturn?: number;
  triggerCount: number;
  winRate: number;
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
