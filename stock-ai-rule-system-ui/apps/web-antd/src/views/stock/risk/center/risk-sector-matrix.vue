<script lang="ts" setup>
import type { RiskObjectListItem } from '#/api/stock/risk/types';

import { Card, Empty, Tag } from 'ant-design-vue';

import { RiskLevelTag, RiskScoreDisplay } from '../shared';
import { RISK_DATA_STATE_LABELS, riskDataState } from './risk-center-state';

defineProps<{
  loading: boolean;
  rows: RiskObjectListItem[];
}>();

const emit = defineEmits<{
  select: [item: RiskObjectListItem];
}>();
</script>

<template>
  <Card :loading="loading" size="small" title="申万一级行业风险矩阵">
    <div v-if="rows.length" class="sector-grid">
      <button
        v-for="item in rows"
        :key="item.object.objectId"
        class="sector-cell"
        type="button"
        @click="emit('select', item)"
      >
        <span class="sector-name">{{ item.name }}</span>
        <template v-if="riskDataState(item.snapshot) === 'ready'">
          <RiskScoreDisplay
            :completeness="item.snapshot.completeness"
            :score="item.snapshot.totalScore"
          />
          <RiskLevelTag :level="item.snapshot.level" />
        </template>
        <Tag v-else color="default">
          {{ RISK_DATA_STATE_LABELS[riskDataState(item.snapshot)] }}
        </Tag>
      </button>
    </div>
    <Empty v-else description="暂无行业风险数据" />
  </Card>
</template>

<style scoped>
.sector-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
}

.sector-cell {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.sector-cell:hover {
  border-color: hsl(var(--primary) / 60%);
}

.sector-name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-weight: 600;
  white-space: nowrap;
}
</style>
