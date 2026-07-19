export const RISK_DECISION_SUPPORT_NOTICE =
  '风险预警仅用于辅助决策，不构成投资建议，不保证收益；风险分是综合指标分数，不代表事件发生概率。';

export type SignalDirection = 'bearish' | 'bullish' | 'watch';
export type RiskLevel = 'critical' | 'normal' | 'warning' | 'watch';
export type RiskGateStatus = 'block' | 'downgrade' | 'normal' | 'notice';
export type RiskObjectType = 'market' | 'sector' | 'stock';
export type RiskHorizon = '1-5d' | '5-20d' | '20-60d';
export type RiskStage = 'easing' | 'fragile' | 'repricing' | 'stampede';
export type RiskDimension = 'A' | 'C' | 'S' | 'T' | 'V';
export type RiskDataQualityStatus =
  | 'available'
  | 'insufficient_history'
  | 'stale'
  | 'unavailable'
  | 'valid_zero';

export interface RiskObjectRef {
  objectId: string;
  objectType: RiskObjectType;
}

export interface RiskEvidence {
  availableAt: string;
  details: Record<string, unknown>;
  dimension: RiskDimension;
  indicatorCode: string;
  observedAt: string;
  qualityStatus: RiskDataQualityStatus;
  rawValue: null | number;
  score: null | number;
  source: string;
}

export interface RiskSnapshot {
  aScore: null | number;
  cScore: null | number;
  calculatedAt: string;
  completeness: number;
  evidence: RiskEvidence[];
  horizon: RiskHorizon;
  level: null | RiskLevel;
  mScore: null | number;
  modelVersion: string;
  object: RiskObjectRef;
  riskConfidence: null | number;
  sScore: null | number;
  stage: null | RiskStage;
  tScore: null | number;
  totalScore: null | number;
  tradeDate: string;
  vScore: null | number;
}

export interface RiskGateDecision {
  calculatedAt: string;
  enforced: false;
  horizon: RiskHorizon;
  modelVersion: string;
  object: RiskObjectRef;
  originalConfidence: number;
  reason: string;
  signalDirection: SignalDirection;
  suggestedAction: RiskGateStatus;
  suggestedConfidence: number;
  tradeDate: string;
}

export interface RiskLevelCount {
  count: number;
  level: RiskLevel;
}

export interface RiskOverview {
  highRiskObjects: RiskObjectListItem[];
  horizon: RiskHorizon;
  levelCounts: RiskLevelCount[];
  marketSnapshot: null | RiskSnapshot;
  riskDisclaimer: string;
  tradeDate: null | string;
}

export interface RiskObjectListItem {
  gateDecision?: RiskGateDecision;
  name: string;
  object: RiskObjectRef;
  parentName?: string;
  parentObject?: RiskObjectRef;
  snapshot: RiskSnapshot;
}

export interface RiskObjectDetail extends RiskObjectListItem {
  activeTriggers: string[];
  parentObjects: RiskObjectRef[];
  snapshots: RiskSnapshot[];
}

export interface RiskTrendPoint {
  aScore: null | number;
  cScore: null | number;
  completeness: number;
  level: null | RiskLevel;
  sScore: null | number;
  tScore: null | number;
  totalScore: null | number;
  tradeDate: string;
  vScore: null | number;
}

export interface RiskOverviewQuery {
  horizon?: RiskHorizon;
  tradeDate?: string;
}

export interface RiskObjectQuery extends RiskOverviewQuery {
  keyword?: string;
  level?: RiskLevel;
  objectType?: RiskObjectType;
  pageNum?: number;
  pageSize?: number;
  parentObjectId?: string;
  parentObjectType?: RiskObjectType;
}

export interface RiskObjectDetailQuery {
  horizon?: RiskHorizon;
  tradeDate?: string;
}

export interface RiskTrendQuery {
  endDate?: string;
  horizon?: RiskHorizon;
  startDate?: string;
}
