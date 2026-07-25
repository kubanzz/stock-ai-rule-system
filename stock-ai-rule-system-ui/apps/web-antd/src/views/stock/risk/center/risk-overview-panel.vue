<script lang="ts" setup>
import type { RiskObjectListItem, RiskOverview } from '#/api/stock/risk/types';

import { Card, Empty, Statistic, Tag } from 'ant-design-vue';

import { RiskLevelTag, RiskScoreDisplay } from '../shared';
import {
  displayRiskAssessment,
  RISK_DATA_STATE_LABELS,
  riskDataState,
} from './risk-center-state';

defineProps<{
  loading: boolean;
  overview?: RiskOverview;
}>();

const emit = defineEmits<{
  select: [item: RiskObjectListItem];
}>();

const levelLabels = {
  critical: '严重',
  normal: '正常',
  warning: '预警',
  watch: '关注',
} as const;
</script>

<template>
  <Card :loading="loading" size="small" title="风险总览">
    <div v-if="overview" class="overview-grid">
      <button
        v-if="overview.marketSnapshot"
        class="market-card"
        type="button"
        @click="
          emit('select', {
            name: 'A 股市场',
            object: overview.marketSnapshot.object,
            snapshot: overview.marketSnapshot,
          })
        "
      >
        <span class="overview-label">市场风险</span>
        <template
          v-if="
            ['formal', 'provisional'].includes(
              riskDataState(overview.marketSnapshot),
            )
          "
        >
          <RiskScoreDisplay
            :completeness="overview.marketSnapshot.completeness"
            :provisional="
              displayRiskAssessment(overview.marketSnapshot).provisional
            "
            :score="displayRiskAssessment(overview.marketSnapshot).score"
          />
          <RiskLevelTag
            :level="displayRiskAssessment(overview.marketSnapshot).level"
          />
        </template>
        <Tag v-else color="default">
          {{ RISK_DATA_STATE_LABELS[riskDataState(overview.marketSnapshot)] }}
        </Tag>
      </button>
      <div v-else class="market-card market-card--empty">市场数据不足</div>

      <Statistic
        v-for="item in overview.levelCounts"
        :key="item.level"
        :title="`${levelLabels[item.level]}对象`"
        :value="item.count"
      />
    </div>
    <div v-if="overview?.highRiskObjects.length" class="high-risk-list">
      <span class="overview-label">高风险对象</span>
      <button
        v-for="item in overview.highRiskObjects"
        :key="`${item.object.objectType}-${item.object.objectId}`"
        class="high-risk-object"
        type="button"
        @click="emit('select', item)"
      >
        {{ item.name }}
        <template
          v-if="
            ['formal', 'provisional'].includes(riskDataState(item.snapshot))
          "
        >
          <RiskLevelTag :level="displayRiskAssessment(item.snapshot).level" />
          <Tag
            v-if="displayRiskAssessment(item.snapshot).provisional"
            color="gold"
          >
            暂定
          </Tag>
        </template>
        <Tag v-else color="default">
          {{ RISK_DATA_STATE_LABELS[riskDataState(item.snapshot)] }}
        </Tag>
      </button>
    </div>
    <Empty v-if="!overview" description="暂无总览数据" />
  </Card>
</template>

<style scoped>
.overview-grid {
  display: grid;
  grid-template-columns: minmax(180px, 1.5fr) repeat(4, minmax(100px, 1fr));
  gap: 12px;
  align-items: stretch;
}

.market-card {
  display: flex;
  gap: 10px;
  align-items: center;
  min-height: 72px;
  padding: 12px;
  text-align: left;
  cursor: pointer;
  background: hsl(var(--accent) / 35%);
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.market-card--empty {
  color: hsl(var(--muted-foreground));
  cursor: default;
}

.overview-label {
  font-size: 13px;
  color: hsl(var(--muted-foreground));
}

.high-risk-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 12px;
}

.high-risk-object {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  padding: 5px 8px;
  cursor: pointer;
  background: transparent;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

@media (max-width: 900px) {
  .overview-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
