<script lang="ts" setup>
import type { RiskDimensionAssessment } from '#/api/stock/risk/types';

import { Empty, Popover, Tag } from 'ant-design-vue';

defineProps<{
  assessment: RiskDimensionAssessment;
}>();

const statusPresentation = {
  insufficient_history: { color: 'gold', label: '历史不足' },
  not_integrated: { color: 'default', label: '尚未接入' },
  source_failed: { color: 'red', label: '源失败' },
  stale: { color: 'orange', label: '已过期' },
  used: { color: 'green', label: '已使用' },
} as const;
</script>

<template>
  <Popover placement="bottomRight" trigger="click">
    <template #content>
      <div class="indicator-list">
        <div
          v-for="item in assessment.indicators"
          :key="item.code"
          class="indicator-row"
        >
          <div class="indicator-title">
            <strong>{{ item.code }} · {{ item.name }}</strong>
            <Tag :color="statusPresentation[item.status].color">
              {{ statusPresentation[item.status].label }}
            </Tag>
          </div>
          <div class="indicator-meta">
            <span>权重 {{ item.weight }}%</span>
            <span v-if="item.score !== null">
              分数 {{ item.score.toFixed(1) }}
            </span>
            <span v-if="item.rawValue !== null">原值 {{ item.rawValue }}</span>
          </div>
          <div v-if="item.used" class="indicator-detail">
            <span>{{ item.source || '未知来源' }}</span>
            <span>可用于 {{ item.availableAt || '--' }}</span>
          </div>
          <div v-else class="indicator-reason">
            {{ item.reason || '当前未参与计算' }}
          </div>
        </div>
        <Empty
          v-if="assessment.indicators.length === 0"
          description="暂无指标目录"
          :image="Empty.PRESENTED_IMAGE_SIMPLE"
        />
      </div>
    </template>
    <button class="indicator-trigger" type="button">
      {{ assessment.usedCount }}/{{ assessment.totalCount }} 可用 ·
      {{ Math.round(assessment.coverage * 100) }}%
    </button>
  </Popover>
</template>

<style scoped>
.indicator-trigger {
  padding: 2px 6px;
  font-size: 12px;
  color: hsl(var(--primary));
  cursor: pointer;
  background: transparent;
  border: 0;
}

.indicator-list {
  display: grid;
  gap: 8px;
  width: min(420px, 75vw);
  max-height: 360px;
  overflow: auto;
}

.indicator-row {
  padding: 9px;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.indicator-title,
.indicator-meta,
.indicator-detail {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.indicator-title {
  justify-content: space-between;
}

.indicator-meta,
.indicator-detail,
.indicator-reason {
  margin-top: 5px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.indicator-reason {
  color: #d46b08;
}
</style>
