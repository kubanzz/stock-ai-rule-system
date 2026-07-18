import type { SignalDashboardRow } from '#/api/stock';
import type { RiskHorizon } from '#/api/stock/risk/types';

import { RISK_DECISION_SUPPORT_NOTICE } from '#/api/stock/risk/types';

type CsvCell = null | number | string | undefined;

const CSV_HEADERS = [
  '股票代码',
  '名称',
  '价格',
  '涨跌幅',
  '系统信号',
  '看涨分',
  '看跌分',
  '原始信号置信度',
  '风险周期',
  '风险强度分',
  '风险等级',
  '风险阶段',
  '风险完整度',
  '风险置信度',
  'V',
  'T',
  'S',
  'C',
  'A',
  'M',
  '证据指标',
  '证据来源',
  '证据质量',
  '证据观测时间',
  '证据可用时间',
  '闸门信号方向',
  '影子闸门原始置信度',
  '影子闸门建议置信度',
  '影子闸门建议动作',
  '影子闸门执行状态',
  '影子闸门原因',
  '规则数',
  '建议周期',
  '更新时间',
  '辅助决策提示',
] as const;

export function buildSignalDashboardCsv(
  rows: SignalDashboardRow[],
  riskHorizon: RiskHorizon,
) {
  return [CSV_HEADERS, ...rows.map((row) => dashboardRow(row, riskHorizon))]
    .map((line) => line.map(escapeCsvCell).join(','))
    .join('\n');
}

function dashboardRow(
  row: SignalDashboardRow,
  riskHorizon: RiskHorizon,
): CsvCell[] {
  const snapshot = row.riskSnapshot;
  const gate = row.riskGateDecision;
  return [
    row.symbol,
    row.name,
    row.price,
    row.changePct,
    row.signalStatus === 'pending' ? '待生成信号' : row.signal,
    row.bullishScore,
    row.bearishScore,
    row.confidence,
    snapshot?.horizon ?? riskHorizon,
    snapshot?.totalScore,
    snapshot?.level,
    snapshot?.stage,
    snapshot ? `${Math.round(snapshot.completeness * 100)}%` : undefined,
    snapshot?.riskConfidence,
    snapshot?.vScore,
    snapshot?.tScore,
    snapshot?.sScore,
    snapshot?.cScore,
    snapshot?.aScore,
    snapshot?.mScore,
    joinEvidence(row, (item) => `${item.dimension}:${item.indicatorCode}`),
    joinEvidence(row, (item) => item.source),
    joinEvidence(row, (item) => item.qualityStatus),
    joinEvidence(row, (item) => item.observedAt),
    joinEvidence(row, (item) => item.availableAt),
    gate?.signalDirection,
    gate?.originalConfidence,
    gate?.suggestedConfidence,
    gate?.suggestedAction,
    gate ? 'enforced=false' : undefined,
    gate?.reason,
    row.triggeredRuleCount,
    row.suggestedPeriod,
    row.updatedAt,
    RISK_DECISION_SUPPORT_NOTICE,
  ];
}

function joinEvidence(
  row: SignalDashboardRow,
  selector: (
    item: NonNullable<SignalDashboardRow['riskSnapshot']>['evidence'][number],
  ) => string,
) {
  return row.riskSnapshot?.evidence.map(selector).join(' | ') ?? '';
}

function escapeCsvCell(value: CsvCell) {
  return `"${String(value ?? '').replaceAll('"', '""')}"`;
}
