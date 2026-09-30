import type { StockAjaxResult } from './ajax-result';

import { baseRequestClient } from '#/api/request';

import { unwrapAjaxResult } from './ajax-result';

export interface SignalBackfillIssue {
  reason: string;
  symbol: string;
  tradeDate: string;
}

export interface SignalBackfillFailure extends SignalBackfillIssue {
  stage: string;
}

export interface SignalBackfillRun {
  backfilledSignalCount: number;
  calculatedFactors: number;
  completedTasks: number;
  failures: SignalBackfillFailure[];
  finishedAt: null | string;
  generatedSignals: number;
  latestCompletedTradeDate: null | string;
  missingQuotes: SignalBackfillIssue[];
  runId: string;
  stage: string;
  startedAt: string;
  status: 'FAILED' | 'PARTIAL' | 'QUEUED' | 'RUNNING' | 'SUCCESS';
  syncedQuotes: number;
  totalDates: number;
  totalSymbols: number;
  totalTasks: number;
}

interface RawResponse<T> {
  data: T;
}

export async function startSignalBackfill(): Promise<SignalBackfillRun> {
  const response = await baseRequestClient.post<
    RawResponse<StockAjaxResult<SignalBackfillRun>>
  >('/signals/backfill-runs');
  return unwrapAjaxResult(response.data);
}

export async function getSignalBackfillRun(
  runId: string,
): Promise<SignalBackfillRun> {
  const response = await baseRequestClient.get<
    RawResponse<StockAjaxResult<SignalBackfillRun>>
  >(`/signals/backfill-runs/${encodeURIComponent(runId)}`);
  return unwrapAjaxResult(response.data);
}

export async function getLatestSignalBackfillRun(): Promise<
  null | SignalBackfillRun
> {
  const response = await baseRequestClient.get<
    RawResponse<StockAjaxResult<null | SignalBackfillRun>>
  >('/signals/backfill-runs/latest');
  return unwrapAjaxResult(response.data) ?? null;
}
