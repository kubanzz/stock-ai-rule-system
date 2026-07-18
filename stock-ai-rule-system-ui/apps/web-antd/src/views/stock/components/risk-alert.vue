<script lang="ts" setup>
import { Alert } from 'ant-design-vue';

import { STOCK_RISK_DISCLAIMER } from '#/api/stock';

withDefaults(
  defineProps<{
    message?: string;
  }>(),
  {
    message: STOCK_RISK_DISCLAIMER,
  },
);
</script>

<template>
  <Alert :message="message" class="mb-4" show-icon type="warning" />
</template>
