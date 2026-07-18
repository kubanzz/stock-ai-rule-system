<script lang="ts" setup>
import type { DashboardQueryState } from './dashboard-state';

import type {
  DashboardMetricCard,
  SignalDashboardOverview,
  SignalDashboardRow,
  WatchlistPool,
} from '#/api/stock';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import { message } from 'ant-design-vue';

import { getSignalDashboard, getWatchlists } from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';
import MarketContextPanel from './components/market-context-panel.vue';
import SignalDashboardToolbar from './components/signal-dashboard-toolbar.vue';
import SignalMetricGrid from './components/signal-metric-grid.vue';
import SignalTable from './components/signal-table.vue';
import StockPickerDrawer from './components/stock-picker-drawer.vue';
import WatchlistManagerDrawer from './components/watchlist-manager-drawer.vue';
import {
  applyDashboardFilters,
  applyDashboardPagination,
  applyStocksAdded,
  createDashboardQuery,
} from './dashboard-state';

const router = useRouter();
const loading = ref(false);
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
  { label: '高风险', tone: 'purple', unit: '条', value: null },
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
  } catch (error) {
    message.error(error instanceof Error ? error.message : '信号看板加载失败');
  } finally {
    loading.value = false;
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
  const headers = [
    '股票代码',
    '名称',
    '价格',
    '涨跌幅',
    '系统信号',
    '看涨分',
    '看跌分',
    '风险分',
    '置信度',
    '规则数',
    '建议周期',
    '更新时间',
  ];
  const values = rows.map((row) => [
    row.symbol,
    row.name ?? '',
    row.price ?? '',
    row.changePct ?? '',
    row.signalStatus === 'pending' ? '待生成信号' : row.signal,
    row.bullishScore ?? '',
    row.bearishScore ?? '',
    row.riskScore ?? '',
    row.confidence ?? '',
    row.triggeredRuleCount,
    row.suggestedPeriod ?? '',
    row.updatedAt ?? '',
  ]);
  const csv = [headers, ...values]
    .map((line) =>
      line.map((value) => `"${String(value).replaceAll('"', '""')}"`).join(','),
    )
    .join('\n');
  const url = URL.createObjectURL(
    new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' }),
  );
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `信号看板-${dashboard.value?.tradeDate ?? 'latest'}.csv`;
  anchor.click();
  URL.revokeObjectURL(url);
}

onMounted(() => loadPageData(true));
</script>

<template>
  <Page
    description="多因子与规则推理后的股票辅助决策信号，不展示确定性预测。"
    title="股票信号看板"
  >
    <div class="signal-dashboard">
      <div class="status-line">
        <span>交易日：{{ dashboard?.tradeDate ?? '暂无' }}</span>
        <span>数据更新：{{ dashboard?.dataUpdatedAt ?? '暂无' }}</span>
        <span :class="loading ? 'status-loading' : 'status-ready'">
          {{ loading ? '数据更新中' : '服务正常' }}
        </span>
      </div>

      <RiskAlert :message="dashboard?.riskDisclaimer" />

      <SignalMetricGrid :loading="loading" :metrics="metrics" />

      <SignalDashboardToolbar
        :loading="loading"
        :query="query"
        :watchlists="watchlists"
        @export="exportRows"
        @filters="updateFilters"
        @manage="managerOpen = true"
        @pick="pickerOpen = true"
        @refresh="loadPageData(true)"
      />

      <div class="workbench-grid">
        <SignalTable
          :industries="dashboard?.availableIndustries ?? []"
          :loading="loading"
          :query="query"
          :rows="dashboard?.signals ?? []"
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
  margin-top: -8px;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.status-line span:last-child {
  margin-left: auto;
}

.status-ready::before,
.status-loading::before {
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
