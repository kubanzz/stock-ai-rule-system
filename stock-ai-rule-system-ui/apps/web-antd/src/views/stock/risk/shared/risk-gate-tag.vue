<script lang="ts" setup>
import type { RiskGateStatus } from '#/api/stock/risk/types';

import { computed } from 'vue';

import { Tag } from 'ant-design-vue';

import { getGatePresentation } from './presentation';

const props = defineProps<{ status: RiskGateStatus }>();
const presentation = computed(() =>
  getGatePresentation(props.status, false),
);
</script>

<template>
  <Tag :color="presentation.color">{{ presentation.label }}</Tag>
</template>
