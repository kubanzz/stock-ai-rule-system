import type { SignalBackfillRun, SignalDashboardRow } from '#/api/stock';

const BACKFILL_STAGE_LABELS: Record<string, string> = {
  CALCULATING: '计算因子与信号',
  CALENDAR: '核对交易日',
  COMPLETED: '已完成',
  QUEUED: '排队中',
  SCANNING: '检查缺失行情',
  SYNCING_QUOTES: '补齐行情',
};

export function backfillStageLabel(stage: string) {
  return BACKFILL_STAGE_LABELS[stage] ?? stage;
}

export function backfillStatusLabel(status: SignalBackfillRun['status']) {
  const labels: Record<SignalBackfillRun['status'], string> = {
    FAILED: '执行失败',
    PARTIAL: '部分完成',
    QUEUED: '排队中',
    RUNNING: '运行中',
    SUCCESS: '已完成',
  };
  return labels[status];
}

export function isHistoricalSignal(
  row: SignalDashboardRow,
  tradeDate?: string,
) {
  if (row.signalFreshness) return row.signalFreshness === 'historical';
  return Boolean(row.signalDate && tradeDate && row.signalDate < tradeDate);
}

export function signalIdentityKey(row: {
  date?: string;
  signalDate?: null | string;
  signalId?: null | number | string;
  strategyCode?: null | string;
  strategyVersion?: null | string;
  symbol?: string;
}) {
  if (row.signalId !== null && row.signalId !== undefined) {
    return `signal:${row.signalId}`;
  }
  return JSON.stringify([
    row.symbol ?? '',
    row.signalDate ?? row.date ?? '',
    row.strategyCode ?? 'LEGACY',
    row.strategyVersion ?? 'legacy',
  ]);
}

export function signalStrategyLabel(row: {
  strategyCode?: null | string;
  strategyName?: null | string;
}) {
  if (!row.strategyCode || row.strategyCode === 'LEGACY') {
    return '历史默认方案';
  }
  return row.strategyName || row.strategyCode;
}

export function backfillProgress(run: SignalBackfillRun) {
  return run.totalTasks > 0
    ? Math.min(100, Math.round((run.completedTasks / run.totalTasks) * 100))
    : 0;
}
