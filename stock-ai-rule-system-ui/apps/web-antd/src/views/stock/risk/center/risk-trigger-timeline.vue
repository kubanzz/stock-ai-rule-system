<script lang="ts" setup>
import type { RiskTriggerTimelineItem } from './risk-center-state';

import { Card, Empty, Tag, Timeline } from 'ant-design-vue';

defineProps<{
  items: RiskTriggerTimelineItem[];
}>();
</script>

<template>
  <Card size="small" title="活跃触发时间线">
    <Timeline v-if="items.length">
      <Timeline.Item v-for="item in items" :key="item.key">
        <div class="trigger-title">
          <span>
            <Tag>{{ item.dimension }}</Tag>
            <span>{{ item.indicatorCode }}</span>
          </span>
          <strong>{{ item.score ?? '数据不足' }}</strong>
        </div>
        <div class="trigger-lineage">
          <span>观测：{{ item.observedAt }}</span>
          <span>可用：{{ item.availableAt }}</span>
          <span>来源：{{ item.source }}</span>
          <span>周期：{{ item.horizon }}</span>
        </div>
      </Timeline.Item>
    </Timeline>
    <Empty v-else description="当前没有活跃触发证据" />
  </Card>
</template>

<style scoped>
.trigger-title {
  display: flex;
  justify-content: space-between;
  margin-bottom: 4px;
}

.trigger-lineage {
  display: grid;
  gap: 2px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>
