<script lang="ts" setup>
import type {
  RiskDimension,
  RiskDimensionAssessment,
  RiskSnapshot,
} from '#/api/stock/risk/types';

import { computed } from 'vue';

import { Progress } from 'ant-design-vue';

import RiskIndicatorPopover from './risk-indicator-popover.vue';

const props = defineProps<{ snapshot: RiskSnapshot }>();
const definitions: Array<{
  code: RiskDimension;
  key: 'aScore' | 'cScore' | 'sScore' | 'tScore' | 'vScore';
  label: string;
}> = [
  { code: 'V', key: 'vScore', label: '结构脆弱' },
  { code: 'T', key: 'tScore', label: '实质触发' },
  { code: 'S', key: 'sScore', label: '外部传导' },
  { code: 'C', key: 'cScore', label: '本地确认' },
  { code: 'A', key: 'aScore', label: '强制卖出' },
];

const rows = computed(() =>
  definitions.map((definition) => {
    const assessment =
      props.snapshot.dimensions?.find(
        (item) => item.dimension === definition.code,
      ) ?? emptyAssessment(definition.code);
    const formal =
      props.snapshot.conclusionStatus === 'formal' ||
      (!props.snapshot.conclusionStatus &&
        props.snapshot.totalScore !== null &&
        props.snapshot.level !== null);
    return {
      ...definition,
      assessment,
      value: formal ? props.snapshot[definition.key] : assessment.score,
    };
  }),
);

function emptyAssessment(
  dimension: RiskDimension,
): RiskDimensionAssessment {
  return {
    coverage: 0,
    dimension,
    indicators: [],
    score: null,
    totalCount: 0,
    usedCount: 0,
  };
}
</script>

<template>
  <div class="space-y-2">
    <div
      v-for="row in rows"
      :key="row.code"
      class="grid grid-cols-[92px_1fr_auto] items-center gap-3"
    >
      <span class="text-sm">{{ row.code }} · {{ row.label }}</span>
      <Progress
        v-if="row.value !== null"
        :percent="row.value"
        :show-info="true"
        size="small"
        status="normal"
      />
      <span v-else class="text-sm text-gray-400">数据不足</span>
      <RiskIndicatorPopover :assessment="row.assessment" />
    </div>
  </div>
</template>
