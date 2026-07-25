<script lang="ts" setup>
import type { RiskSyncJob } from '#/api/stock/risk/types';

import { computed } from 'vue';

import { Button, Progress, Tag } from 'ant-design-vue';

const props = defineProps<{
  job?: RiskSyncJob;
  latestDataDate?: null | string;
  staleTradingDays?: number;
}>();

const emit = defineEmits<{
  syncMarket: [];
}>();

const active = computed(
  () => props.job?.status === 'queued' || props.job?.status === 'running',
);
const statusPresentation = computed(() => {
  switch (props.job?.status) {
    case 'failed': {
      return { color: 'red', label: '同步失败' };
    }
    case 'partial_success': {
      return { color: 'gold', label: '部分成功' };
    }
    case 'queued': {
      return { color: 'blue', label: '等待执行' };
    }
    case 'running': {
      return { color: 'processing', label: '同步中' };
    }
    case 'succeeded': {
      return { color: 'green', label: '同步完成' };
    }
    default: {
      return { color: 'default', label: '尚未同步' };
    }
  }
});

const phaseLabels: Record<string, string> = {
  completed: '已完成',
  resolving_trade_date: '确定最近交易日',
  scoring: '计算风险快照',
  syncing_industry: '同步申万行业',
  syncing_market: '同步市场与行业',
  syncing_stock: '同步市场、行业与个股',
};
</script>

<template>
  <section class="sync-status">
    <div class="sync-summary">
      <div>
        <span class="sync-label">最新风险数据</span>
        <strong>{{ latestDataDate || '暂无' }}</strong>
      </div>
      <div>
        <span class="sync-label">距最近交易日</span>
        <strong>
          {{ latestDataDate ? `${staleTradingDays ?? 0} 个交易日` : '--' }}
        </strong>
      </div>
      <div>
        <span class="sync-label">同步状态</span>
        <span>
          <Tag :color="statusPresentation.color">
            {{ statusPresentation.label }}
          </Tag>
          {{ job ? phaseLabels[job.phase] || job.phase : '可手动获取最新数据' }}
        </span>
      </div>
    </div>

    <div v-if="job" class="sync-progress">
      <Progress
        v-if="active"
        :percent="job.progress"
        :show-info="false"
        size="small"
        status="active"
      />
      <span
        v-if="job.message"
        :class="{ 'sync-error': job.status === 'failed' }"
      >
        {{ job.message }}
      </span>
      <span v-else-if="active">后台同步中，当前页面数据仍可正常查看。</span>
    </div>

    <Button :loading="active" type="primary" @click="emit('syncMarket')">
      同步最新市场数据
    </Button>
  </section>
</template>

<style scoped>
.sync-status {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px 16px;
  align-items: center;
  padding: 12px 14px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.sync-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 28px;
  align-items: center;
}

.sync-summary > div {
  display: grid;
  gap: 2px;
}

.sync-label {
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.sync-progress {
  display: grid;
  grid-column: 1 / -1;
  gap: 4px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.sync-error {
  color: #cf1322;
}

@media (max-width: 720px) {
  .sync-status {
    grid-template-columns: 1fr;
  }
}
</style>
