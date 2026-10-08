<script lang="ts" setup>
import type { DashboardQueryState } from '../dashboard-state';

import type { WatchlistPool } from '#/api/stock';
import type { RuleStrategy } from '#/api/stock/rule-combinations';

import { computed } from 'vue';

import { Download, Plus, RotateCw, Settings } from '@vben/icons';

import { Button, DatePicker, Select, Tooltip } from 'ant-design-vue';

import { RISK_HORIZON_OPTIONS } from '../risk-dashboard-state';

const props = defineProps<{
  backfillRunning?: boolean;
  loading?: boolean;
  query: DashboardQueryState;
  strategies?: RuleStrategy[];
  watchlists: WatchlistPool[];
}>();

const emit = defineEmits<{
  backfill: [];
  export: [];
  filters: [patch: Partial<DashboardQueryState>];
  manage: [];
  pick: [];
  refresh: [];
}>();

const poolOptions = computed(() =>
  props.watchlists.map((pool) => ({
    label: `${pool.poolName} (${pool.total})`,
    value: pool.poolId,
  })),
);

const strategyOptions = computed(() => [
  { label: '历史默认方案', value: 'LEGACY' },
  ...(props.strategies ?? []).map((strategy) => ({
    label: `${strategy.strategyName}（${strategy.strategyCode} · ${strategy.status === 'active' ? '已启用' : '历史配置'}）`,
    value: strategy.strategyCode,
  })),
]);

const selectedPoolLabel = computed(
  () =>
    poolOptions.value.find((option) => option.value === props.query.poolCode)
      ?.label,
);

const selectedStrategyLabel = computed(
  () =>
    strategyOptions.value.find(
      (option) => option.value === props.query.strategyCode,
    )?.label,
);

const marketOptions = [
  { label: 'A股', value: 'A股' },
  { label: '港股', value: '港股' },
  { label: '美股', value: '美股' },
];

function updatePool(value: unknown) {
  emit('filters', { poolCode: typeof value === 'string' ? value : undefined });
}

function updateMarket(value: unknown) {
  emit('filters', { market: typeof value === 'string' ? value : undefined });
}

function updateStrategy(value: unknown) {
  emit('filters', {
    strategyCode: typeof value === 'string' && value ? value : undefined,
  });
}

function updateDate(value: unknown) {
  emit('filters', {
    date: typeof value === 'string' && value ? value : undefined,
  });
}

function updateRiskHorizon(value: unknown) {
  emit('filters', {
    riskHorizon:
      typeof value === 'string'
        ? (value as DashboardQueryState['riskHorizon'])
        : undefined,
  });
}
</script>

<template>
  <div class="toolbar-shell">
    <div class="toolbar-filters">
      <div class="field-group field-pool">
        <span class="field-label">股票池</span>
        <Tooltip :title="selectedPoolLabel">
          <Select
            :options="poolOptions"
            :value="query.poolCode"
            @update:value="updatePool"
          />
        </Tooltip>
      </div>
      <div class="field-group field-strategy">
        <span class="field-label">应用方案</span>
        <Tooltip :title="selectedStrategyLabel">
          <Select
            allow-clear
            show-search
            option-filter-prop="label"
            placeholder="全部方案"
            :options="strategyOptions"
            :value="query.strategyCode"
            @update:value="updateStrategy"
          />
        </Tooltip>
      </div>
      <div class="field-group field-market">
        <span class="field-label">市场</span>
        <Select
          :options="marketOptions"
          :value="query.market"
          @update:value="updateMarket"
        />
      </div>
      <div class="field-group field-date">
        <span class="field-label">交易日</span>
        <DatePicker
          :value="query.date"
          allow-clear
          value-format="YYYY-MM-DD"
          @update:value="updateDate"
        />
      </div>
      <div class="field-group field-risk-horizon">
        <span class="field-label">风险周期</span>
        <Select
          :options="RISK_HORIZON_OPTIONS"
          :value="query.riskHorizon"
          @update:value="updateRiskHorizon"
        />
      </div>
    </div>

    <div class="toolbar-actions">
      <Tooltip
        :title="
          query.market === 'A股'
            ? '按所有已启用方案的股票范围补齐行情，各方案独立运算；未绑定分组时使用我的关注。上方股票池和方案用于筛选查看结果。'
            : '目前仅支持 A 股行情与信号补齐'
        "
      >
        <Button
          :disabled="query.market !== 'A股' || loading"
          :loading="backfillRunning"
          @click="emit('backfill')"
        >
          同步并重算
        </Button>
      </Tooltip>
      <Button @click="emit('manage')">
        <Settings class="button-icon" />管理股票池
      </Button>
      <Button type="primary" @click="emit('pick')">
        <Plus class="button-icon" />添加股票
      </Button>
      <Tooltip title="刷新数据">
        <Button
          :loading="loading"
          aria-label="刷新数据"
          @click="emit('refresh')"
        >
          <RotateCw class="button-icon icon-only" />
        </Button>
      </Tooltip>
      <Tooltip title="导出当前结果">
        <Button aria-label="导出当前结果" @click="emit('export')">
          <Download class="button-icon icon-only" />
        </Button>
      </Tooltip>
    </div>
  </div>
</template>

<style scoped>
.toolbar-shell {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  padding: 14px 16px;
  container: signal-toolbar / inline-size;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.toolbar-filters {
  display: grid;
  grid-template-columns:
    minmax(0, 3fr) minmax(0, 2.5fr) minmax(0, 1fr)
    minmax(0, 1.4fr) minmax(0, 1.4fr);
  gap: 12px 16px;
  align-items: end;
}

.field-group {
  display: grid;
  gap: 6px;
  min-width: 0;
}

.field-group :deep(.ant-select),
.field-group :deep(.ant-picker) {
  width: 100%;
  min-width: 0;
}

.field-group :deep(.ant-select-selector) {
  min-width: 0;
}

.field-group :deep(.ant-select-selection-item) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-label {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  justify-content: flex-end;
  padding-top: 12px;
  border-top: 1px solid hsl(var(--border));
}

.toolbar-actions :deep(.ant-btn) {
  flex-shrink: 0;
}

.button-icon {
  width: 15px;
  height: 15px;
  margin-right: 6px;
}

.icon-only {
  margin-right: 0;
}

@container signal-toolbar (max-width: 1080px) {
  .toolbar-filters {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }

  .field-pool,
  .field-strategy {
    grid-column: span 3;
  }

  .field-market,
  .field-date,
  .field-risk-horizon {
    grid-column: span 2;
  }
}

@container signal-toolbar (max-width: 680px) {
  .toolbar-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .field-pool,
  .field-strategy {
    grid-column: 1 / -1;
  }

  .field-market,
  .field-date,
  .field-risk-horizon {
    grid-column: auto;
  }

  .toolbar-actions {
    justify-content: flex-start;
  }
}

@container signal-toolbar (max-width: 420px) {
  .toolbar-filters {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
