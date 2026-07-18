<script lang="ts" setup>
import type { RiskSnapshot } from '#/api/stock/risk/types';

import { computed } from 'vue';

import { Progress } from 'ant-design-vue';

const props = defineProps<{ snapshot: RiskSnapshot }>();
const rows = computed(() => [
  { code: 'V', label: '结构脆弱', value: props.snapshot.vScore },
  { code: 'T', label: '实质触发', value: props.snapshot.tScore },
  { code: 'S', label: '外部传导', value: props.snapshot.sScore },
  { code: 'C', label: '本地确认', value: props.snapshot.cScore },
  { code: 'A', label: '强制卖出', value: props.snapshot.aScore },
]);
</script>

<template>
  <div class="space-y-2">
    <div v-for="row in rows" :key="row.code" class="grid grid-cols-[92px_1fr] items-center gap-3">
      <span class="text-sm">{{ row.code }} · {{ row.label }}</span>
      <Progress
        v-if="row.value !== null"
        :percent="row.value"
        :show-info="true"
        size="small"
        status="normal"
      />
      <span v-else class="text-sm text-gray-400">数据不足</span>
    </div>
  </div>
</template>
