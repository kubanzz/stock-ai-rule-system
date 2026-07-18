<script lang="ts" setup>
import type { RiskLevel } from '#/api/stock/risk/types';

import { computed } from 'vue';

import { Tag } from 'ant-design-vue';

import { getRiskLevelPresentation } from './presentation';

const props = defineProps<{ level?: null | RiskLevel }>();
const presentation = computed(() => getRiskLevelPresentation(props.level));
</script>

<template>
  <Tag :color="presentation.color">{{ presentation.label }}</Tag>
</template>
