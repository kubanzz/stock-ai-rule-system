<script lang="ts" setup>
import type { DashboardQueryState } from './dashboard-state';

import type {
  DashboardMetricCard,
  SignalBackfillRun,
  SignalDashboardOverview,
  SignalDashboardRow,
  WatchlistPool,
} from '#/api/stock';

import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { Alert, Progress, message } from 'ant-design-vue';

import {
  getLatestSignalBackfillRun,
  getSignalBackfillRun,
  getSignalDashboard,
  getWatchlists,
  startSignalBackfill,
} from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';
import MarketContextPanel from './components/market-context-panel.vue';
import SignalDashboardToolbar from './components/signal-dashboard-toolbar.vue';
import SignalMetricGrid from './components/signal-metric-grid.vue';
import SignalTable from './components/signal-table.vue';
import StockPickerDrawer from './components/stock-picker-drawer.vue';
import WatchlistManagerDrawer from './components/watchlist-manager-drawer.vue';
import { buildSignalDashboardCsv } from './dashboard-export';
import {
  backfillProgress,
  backfillStageLabel,
  backfillStatusLabel,
} from './dashboard-display';
import {
  applyDashboardFilters,
  applyDashboardPagination,
  applyStocksAdded,
  createDashboardQuery,
} from './dashboard-state';

const router = useRouter();
const loading = ref(false);
const loadError = ref('');
const lastSuccessfulLoadAt = ref('');
const backfillRun = ref<SignalBackfillRun>();
const backfillError = ref('');
const startingBackfill = ref(false);
const backfillRunning = computed(
  () =>
    startingBackfill.value ||
    backfillRun.value?.status === 'QUEUED' ||
    backfillRun.value?.status === 'RUNNING',
);
let backfillTimer: ReturnType<typeof setTimeout> | undefined;
let disposed = false;
const managerOpen = ref(false);
const pickerOpen = ref(false);
const dashboard = ref<SignalDashboardOverview>();
const watchlists = ref<WatchlistPool[]>([]);
const query = reactive<DashboardQueryState>(createDashboardQuery());

const initialMetrics: DashboardMetricCard[] = [
  { label: '关注股票', tone: 'blue', unit: '只', value: null },
  { label: '产生信号', tone: 'cyan', unit: '条', value: null },
  { label: '看涨', tone: 'green', unit: '条', value: null },
  { label: '看跌', tone: 'red', unit: '条', value: null },
  { label: '观望', tone: 'gold', unit: '条', value: null },
  { label: '严重风险', tone: 'purple', unit: '个', value: null },
];

const metrics = computed(() => dashboard.value?.metrics ?? initialMetrics);

async function loadPageData(reloadPools = false) {
  loading.value = true;
  try {
    const dashboardPromise = getSignalDashboard({ ...query });
    if (reloadPools || watchlists.value.length === 0) {
      const [dashboardResult, poolResult] = await Promise.all([
        dashboardPromise,
        getWatchlists({ market: query.market }),
      ]);
      dashboard.value = dashboardResult;
      watchlists.value = poolResult;
      if (!poolResult.some((pool) => pool.poolId === query.poolCode)) {
        const fallback =
          poolResult.find((pool) => pool.poolId === 'my-follow') ??
          poolResult[0];
        if (fallback) query.poolCode = fallback.poolId;
      }
    } else {
      dashboard.value = await dashboardPromise;
    }
    loadError.value = '';
    lastSuccessfulLoadAt.value = new Date().toLocaleString('zh-CN', {
      hour12: false,
    });
  } catch (error) {
    loadError.value =
      error instanceof Error ? error.message : '信号看板加载失败';
    message.error(loadError.value);
  } finally {
    loading.value = false;
  }
}

async function pollBackfill(runId: string) {
  try {
    const result = await getSignalBackfillRun(runId);
    if (disposed) return;
    backfillRun.value = result;
    backfillError.value = '';
    if (result.status === 'QUEUED' || result.status === 'RUNNING') {
      scheduleBackfillPoll(runId);
      return;
    }
    if (result.status === 'SUCCESS') {
      message.success('行情补齐与信号重算已完成');
    } else {
      message.warning('补齐任务未全部完成，请查看缺口与失败详情');
    }
    Object.assign(
      query,
      applyDashboardFilters(
        { ...query },
        {
          confidenceMax: undefined,
          confidenceMin: undefined,
          date: undefined,
          industry: undefined,
          market: 'A股',
          poolCode: 'my-follow',
          signal: undefined,
          symbol: undefined,
        },
      ),
    );
    await loadPageData(true);
  } catch (error) {
    if (disposed) return;
    backfillError.value =
      error instanceof Error ? error.message : '查询补齐进度失败';
    scheduleBackfillPoll(runId, 5000);
  }
}

async function restoreLatestBackfill() {
  try {
    const latest = await getLatestSignalBackfillRun();
    if (disposed || !latest) return;
    backfillRun.value = latest;
    if (latest.status === 'QUEUED' || latest.status === 'RUNNING') {
      scheduleBackfillPoll(latest.runId);
    }
  } catch (error) {
    if (disposed) return;
    backfillError.value =
      error instanceof Error ? error.message : '读取最近补齐任务失败';
  }
}

function scheduleBackfillPoll(runId: string, delay = 2000) {
  if (backfillTimer) clearTimeout(backfillTimer);
  backfillTimer = setTimeout(() => {
    backfillTimer = undefined;
    void pollBackfill(runId);
  }, delay);
}

async function runBackfill() {
  if (backfillRunning.value) return;
  startingBackfill.value = true;
  backfillError.value = '';
  try {
    const result = await startSignalBackfill();
    if (disposed) return;
    backfillRun.value = result;
    if (result.status === 'QUEUED' || result.status === 'RUNNING') {
      scheduleBackfillPoll(result.runId);
    } else {
      await pollBackfill(result.runId);
    }
  } catch (error) {
    if (disposed) return;
    backfillError.value =
      error instanceof Error ? error.message : '启动补齐任务失败';
    message.error(backfillError.value);
  } finally {
    startingBackfill.value = false;
  }
}

async function updateFilters(patch: Partial<DashboardQueryState>) {
  const marketChanged =
    patch.market !== undefined && patch.market !== query.market;
  const normalizedPatch = marketChanged ? { ...patch, poolCode: 'all' } : patch;
  Object.assign(query, applyDashboardFilters({ ...query }, normalizedPatch));
  await loadPageData(marketChanged);
}

async function updatePage(pageNum: number, pageSize: number) {
  Object.assign(
    query,
    applyDashboardPagination({ ...query }, pageNum, pageSize),
  );
  await loadPageData();
}

async function resetTableFilters() {
  Object.assign(
    query,
    applyDashboardFilters(
      { ...query },
      {
        confidenceMax: undefined,
        confidenceMin: undefined,
        industry: undefined,
        signal: undefined,
        symbol: undefined,
      },
    ),
  );
  await loadPageData();
}

async function reloadWatchlists() {
  watchlists.value = await getWatchlists({ market: query.market });
  if (!watchlists.value.some((pool) => pool.poolId === query.poolCode)) {
    query.poolCode = 'all';
  }
  await loadPageData();
}

async function handleStocksAdded(change: {
  addedSymbols: string[];
  poolCode: string;
}) {
  watchlists.value = await getWatchlists({ market: query.market });
  Object.assign(query, applyStocksAdded({ ...query }, change.poolCode));
  await loadPageData();
}

function openDetail(row: SignalDashboardRow) {
  router.push({
    name: 'StockDetail',
    params: { symbol: row.symbol },
    query: { date: query.date ?? dashboard.value?.tradeDate },
  });
}

function exportRows() {
  const rows = dashboard.value?.signals ?? [];
  if (rows.length === 0) {
    message.warning('当前筛选结果为空');
    return;
  }
  const csv = buildSignalDashboardCsv(
    rows,
    query.riskHorizon ?? '1-5d',
    dashboard.value?.tradeDate,
  );
  const url = URL.createObjectURL(
    new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }),
  );
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `信号看板-${dashboard.value?.tradeDate ?? 'latest'}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}

onMounted(() => {
  void loadPageData(true);
  void restoreLatestBackfill();
});
onUnmounted(() => {
  disposed = true;
  if (backfillTimer) clearTimeout(backfillTimer);
});
</script>

<template>
  <Page
    description="多因子与规则推理后的股票辅助决策信号，不展示确定性预测。"
    title="股票信号看板"
  >
    <div class="signal-dashboard">
      <div class="status-line">
        <span>基准行情交易日：{{ dashboard?.tradeDate ?? '暂无' }}</span>
        <span>最新信号交易日：{{ dashboard?.latestSignalDate ?? '暂无' }}</span>
        <span>行情同步：{{ dashboard?.quoteUpdatedAt ?? '暂无' }}</span>
        <span>信号生成：{{ dashboard?.signalUpdatedAt ?? '暂无' }}</span>
        <span
          :class="
            loading
              ? 'status-loading'
              : loadError
                ? 'status-error'
                : 'status-ready'
          "
        >
          {{ loading ? '读取中' : loadError ? '读取失败' : '读取成功' }}
        </span>
      </div>

      <Alert
        v-if="loadError"
        :message="`看板读取失败：${loadError}`"
        :description="`当前显示的可能是上次读取的数据；上次成功读取：${lastSuccessfulLoadAt || '暂无'}。请点击刷新数据重试。`"
        show-icon
        type="error"
      />

      <Alert
        v-if="
          dashboard?.latestSignalDate &&
          dashboard.latestSignalDate < (dashboard.tradeDate ?? '')
        "
        :message="`最新信号停留在 ${dashboard.latestSignalDate}，晚于该日的行情不代表信号已重新计算。`"
        show-icon
        type="warning"
      />

      <RiskAlert :message="dashboard?.riskDisclaimer" />

      <SignalMetricGrid :loading="loading" :metrics="metrics" />

      <SignalDashboardToolbar
        :backfill-running="backfillRunning"
        :loading="loading"
        :query="query"
        :watchlists="watchlists"
        @backfill="runBackfill"
        @export="exportRows"
        @filters="updateFilters"
        @manage="managerOpen = true"
        @pick="pickerOpen = true"
        @refresh="loadPageData(true)"
      />

      <section v-if="backfillRun || backfillError" class="backfill-status">
        <Alert
          v-if="backfillError"
          :message="`补齐任务进度暂时无法获取：${backfillError}`"
          show-icon
          type="error"
        />
        <template v-if="backfillRun">
          <div class="backfill-title">
            <strong
              >我的关注 A 股补齐任务：{{
                backfillStatusLabel(backfillRun.status)
              }}</strong
            >
            <span>{{ backfillStageLabel(backfillRun.stage) }}</span>
          </div>
          <Progress
            v-if="
              backfillRun.status === 'QUEUED' ||
              backfillRun.status === 'RUNNING'
            "
            :percent="backfillProgress(backfillRun)"
            size="small"
          />
          <div class="backfill-summary">
            <span
              >全部关注股票最近有完整行情的交易日：{{
                backfillRun.latestCompletedTradeDate ?? '暂无'
              }}</span
            >
            <span>股票：{{ backfillRun.totalSymbols }} 只</span>
            <span>交易日：{{ backfillRun.totalDates }} 天</span>
            <span
              >已处理：{{ backfillRun.completedTasks }}/{{
                backfillRun.totalTasks
              }}
              项</span
            >
            <span>补齐行情：{{ backfillRun.syncedQuotes }} 条</span>
            <span>计算因子：{{ backfillRun.calculatedFactors }} 条</span>
            <span
              >生成信号：{{ backfillRun.generatedSignals }} 条（历史补算
              {{ backfillRun.backfilledSignalCount }} 条）</span
            >
          </div>
          <details
            v-if="
              backfillRun.missingQuotes.length || backfillRun.failures.length
            "
          >
            <summary>
              行情缺口 {{ backfillRun.missingQuotes.length }} 项，失败
              {{ backfillRun.failures.length }} 项
            </summary>
            <div class="backfill-issues">
              <div
                v-for="item in backfillRun.missingQuotes"
                :key="`missing-${item.symbol}-${item.tradeDate}`"
              >
                行情缺口：{{ item.symbol }} · {{ item.tradeDate }} ·
                {{ item.reason }}
              </div>
              <div
                v-for="item in backfillRun.failures"
                :key="`failed-${item.symbol}-${item.tradeDate}-${item.stage}`"
              >
                处理失败：{{ item.symbol }} · {{ item.tradeDate }} ·
                {{ backfillStageLabel(item.stage) }} · {{ item.reason }}
              </div>
            </div>
          </details>
        </template>
      </section>

      <div class="workbench-grid">
        <SignalTable
          :industries="dashboard?.availableIndustries ?? []"
          :loading="loading"
          :query="query"
          :rows="dashboard?.signals ?? []"
          :trade-date="dashboard?.tradeDate"
          :total="dashboard?.total ?? 0"
          @detail="openDetail"
          @filters="updateFilters"
          @page="updatePage"
          @reset="resetTableFilters"
        />
        <MarketContextPanel :context="dashboard?.marketContext" />
      </div>

      <WatchlistManagerDrawer
        v-model:open="managerOpen"
        :market="query.market ?? 'A股'"
        :pools="watchlists"
        @changed="reloadWatchlists"
      />
      <StockPickerDrawer
        v-model:open="pickerOpen"
        :default-pool-id="query.poolCode"
        :pools="watchlists"
        @changed="handleStocksAdded"
      />
    </div>
  </Page>
</template>

<style scoped>
.signal-dashboard {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.status-line {
  display: flex;
  gap: 18px;
  align-items: center;
  flex-wrap: wrap;
  margin-top: -8px;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.status-line span:last-child {
  margin-left: auto;
}

.status-ready::before,
.status-loading::before,
.status-error::before {
  display: inline-block;
  width: 7px;
  height: 7px;
  margin-right: 6px;
  content: '';
  border-radius: 50%;
}

.status-ready::before {
  background: #16a36a;
}

.status-loading::before {
  background: #d89614;
}

.status-error::before {
  background: #e5484d;
}

.backfill-status {
  display: grid;
  gap: 8px;
  padding: 12px 16px;
  font-size: 12px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.backfill-title,
.backfill-summary {
  display: flex;
  gap: 8px 18px;
  flex-wrap: wrap;
  align-items: center;
}

.backfill-title span,
.backfill-summary,
.backfill-issues {
  color: hsl(var(--muted-foreground));
}

.backfill-issues {
  display: grid;
  gap: 4px;
  margin-top: 8px;
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  gap: 14px;
  align-items: start;
  min-width: 0;
}

@media (max-width: 1180px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .status-line {
    flex-direction: column;
    gap: 4px;
    align-items: flex-start;
  }

  .status-line span:last-child {
    margin-left: 0;
  }
}
</style>
