<script lang="ts" setup>
import type { DashboardMetricCard } from '#/api/stock';

import { Card, Skeleton } from 'ant-design-vue';

defineProps<{
  loading?: boolean;
  metrics: DashboardMetricCard[];
}>();

function displayValue(metric: DashboardMetricCard) {
  if (metric.value === null) return '--';
  const value =
    metric.unit === '%' ? metric.value.toFixed(1) : Math.round(metric.value);
  return `${value}${metric.unit ?? ''}`;
}
</script>

<template>
  <div class="metric-grid">
    <Card
      v-for="metric in metrics"
      :key="metric.label"
      class="metric-card"
      :class="`tone-${metric.tone ?? 'blue'}`"
      size="small"
    >
      <Skeleton v-if="loading" :paragraph="false" active />
      <template v-else>
        <div class="metric-label">{{ metric.label }}</div>
        <div class="metric-value">{{ displayValue(metric) }}</div>
        <div v-if="metric.change !== undefined" class="metric-change">
          较前一交易日
          <span :class="metric.change >= 0 ? 'positive' : 'negative'">
            {{ metric.change >= 0 ? '+' : '' }}{{ metric.change }}
          </span>
        </div>
        <div v-else class="metric-change muted">当前筛选口径</div>
      </template>
    </Card>
  </div>
</template>

<style scoped>
.metric-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.metric-card {
  min-height: 112px;
  overflow: hidden;
  border-top: 3px solid var(--metric-color, #1677ff);
  border-radius: 6px;
}

.tone-green {
  --metric-color: #16a36a;
}

.tone-red {
  --metric-color: #e5484d;
}

.tone-gold {
  --metric-color: #d89614;
}

.tone-purple {
  --metric-color: #7c5cfc;
}

.tone-cyan {
  --metric-color: #08979c;
}

.tone-blue {
  --metric-color: #1677ff;
}

.metric-label {
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}

.metric-value {
  margin-top: 8px;
  font-size: 27px;
  font-weight: 650;
  line-height: 1.1;
  color: hsl(var(--foreground));
}

.metric-change {
  margin-top: 10px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.positive {
  color: #16a36a;
}

.negative {
  color: #e5484d;
}

.muted {
  opacity: 0.75;
}

@media (max-width: 1150px) {
  .metric-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-value {
    font-size: 23px;
  }
}
</style>
