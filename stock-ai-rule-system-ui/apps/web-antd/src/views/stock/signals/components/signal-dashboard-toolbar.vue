<script lang="ts" setup>
import type { DashboardQueryState } from '../dashboard-state';

import type { WatchlistPool } from '#/api/stock';

import { computed } from 'vue';

import { Download, Plus, RotateCw, Settings } from '@vben/icons';

import { Button, DatePicker, Select, Tooltip } from 'ant-design-vue';

import { RISK_HORIZON_OPTIONS } from '../risk-dashboard-state';

const props = defineProps<{
  loading?: boolean;
  query: DashboardQueryState;
  watchlists: WatchlistPool[];
}>();

const emit = defineEmits<{
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
        <Select
          :options="poolOptions"
          :value="query.poolCode"
          @update:value="updatePool"
        />
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
  gap: 16px;
  align-items: flex-end;
  justify-content: space-between;
  padding: 14px 16px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.toolbar-filters,
.toolbar-actions {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.field-group {
  display: grid;
  gap: 6px;
}

.field-label {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.field-pool {
  width: 190px;
}

.field-market {
  width: 112px;
}

.field-date {
  width: 148px;
}

.field-risk-horizon {
  width: 152px;
}

.button-icon {
  width: 15px;
  height: 15px;
  margin-right: 6px;
}

.icon-only {
  margin-right: 0;
}

@media (max-width: 900px) {
  .toolbar-shell {
    flex-direction: column;
    align-items: stretch;
  }

  .toolbar-filters {
    display: grid;
    grid-template-columns: 1fr 112px 148px 152px;
  }

  .field-pool,
  .field-market,
  .field-date,
  .field-risk-horizon {
    width: auto;
  }

  .toolbar-actions {
    flex-wrap: wrap;
  }
}

@media (max-width: 640px) {
  .toolbar-filters {
    grid-template-columns: 1fr 1fr;
  }

  .field-date {
    grid-column: auto;
  }
}
</style>
