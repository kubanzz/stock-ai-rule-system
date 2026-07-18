<script lang="ts" setup>
import type { RiskEvidence } from '#/api/stock/risk/types';

import { Tag } from 'ant-design-vue';

import { formatRiskScore } from './presentation';

defineProps<{ evidence: RiskEvidence[] }>();
</script>

<template>
  <div v-if="evidence.length" class="space-y-2">
    <div
      v-for="item in evidence"
      :key="`${item.indicatorCode}-${item.source}-${item.availableAt}`"
      class="rounded border border-gray-200 p-3"
    >
      <div class="flex items-center justify-between gap-3">
        <div>
          <Tag>{{ item.dimension }}</Tag>
          <strong>{{ item.indicatorCode }}</strong>
        </div>
        <span>{{ formatRiskScore(item.score) }}</span>
      </div>
      <div class="mt-2 text-xs text-gray-500">
        来源：{{ item.source }} · 可用时间：{{ item.availableAt }} ·
        质量：{{ item.qualityStatus }}
      </div>
    </div>
  </div>
  <div v-else class="py-4 text-center text-gray-400">暂无可用证据</div>
</template>
