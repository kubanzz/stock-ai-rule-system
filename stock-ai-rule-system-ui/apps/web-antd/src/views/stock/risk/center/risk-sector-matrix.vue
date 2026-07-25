<script lang="ts" setup>
import type { RiskObjectListItem } from '#/api/stock/risk/types';

import { Card, Empty, Tag } from 'ant-design-vue';

import { RiskLevelTag, RiskScoreDisplay } from '../shared';
import {
  displayRiskAssessment,
  RISK_DATA_STATE_LABELS,
  riskDataState,
} from './risk-center-state';

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
        <span class="sector-identity">
          <strong>{{ item.name }}</strong>
          <small>{{ item.object.objectId }}</small>
          <small>数据日 {{ item.snapshot.tradeDate }}</small>
        </span>
        <template
          v-if="
            ['formal', 'provisional'].includes(riskDataState(item.snapshot))
          "
        >
          <RiskScoreDisplay
            :completeness="item.snapshot.completeness"
            :provisional="displayRiskAssessment(item.snapshot).provisional"
            :score="displayRiskAssessment(item.snapshot).score"
          />
          <span class="sector-status">
            <RiskLevelTag :level="displayRiskAssessment(item.snapshot).level" />
            <Tag
              v-if="displayRiskAssessment(item.snapshot).provisional"
              color="gold"
            >
              暂定
            </Tag>
          </span>
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
  grid-template-columns: minmax(0, 1fr) auto auto;
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

.sector-identity {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sector-identity strong,
.sector-identity small {
  display: block;
}

.sector-identity small {
  margin-top: 2px;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.sector-status {
  display: inline-flex;
  gap: 4px;
}
</style>
