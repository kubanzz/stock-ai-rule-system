<script lang="ts" setup>
import { computed } from 'vue';

import { formatRiskScore, getRiskScoreTone } from './presentation';

const props = defineProps<{
  completeness?: number;
  score?: null | number;
}>();
const tone = computed(() => getRiskScoreTone(props.score));
const display = computed(() => formatRiskScore(props.score));
</script>

<template>
  <span class="risk-score" :class="`risk-score--${tone}`">
    {{ display }}
    <small v-if="score !== null && score !== undefined">/100</small>
    <small v-else-if="completeness !== undefined">
      （完整度 {{ Math.round(completeness * 100) }}%）
    </small>
  </span>
</template>

<style scoped>
.risk-score {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.risk-score small {
  margin-left: 2px;
  font-weight: 400;
  color: rgb(100 116 139);
}

.risk-score--high {
  color: #cf1322;
}

.risk-score--medium {
  color: #d46b08;
}

.risk-score--low {
  color: #389e0d;
}

.risk-score--unavailable {
  color: #8c8c8c;
}
</style>
