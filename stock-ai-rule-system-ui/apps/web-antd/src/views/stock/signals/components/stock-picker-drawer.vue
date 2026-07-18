<script lang="ts" setup>
import type { WatchlistCandidate, WatchlistPool } from '#/api/stock';

import { computed, onUnmounted, ref, watch } from 'vue';

import { Search } from '@vben/icons';

import {
  Button,
  Checkbox,
  Drawer,
  Empty,
  Input,
  message,
  Pagination,
  Select,
  Spin,
} from 'ant-design-vue';

import {
  addWatchlistStocksBatch,
  getWatchlistStockCandidates,
} from '#/api/stock';

const props = defineProps<{
  defaultPoolId?: string;
  open: boolean;
  pools: WatchlistPool[];
}>();

const emit = defineEmits<{
  changed: [change: { addedSymbols: string[]; poolCode: string }];
  'update:open': [open: boolean];
}>();

const candidates = ref<WatchlistCandidate[]>([]);
const groupName = ref('');
const keyword = ref('');
const loading = ref(false);
const pageNum = ref(1);
const pageSize = 20;
const saving = ref(false);
const selectedSymbols = ref<string[]>([]);
const targetPoolId = ref('my-follow');
const total = ref(0);
let searchTimer: ReturnType<typeof setTimeout> | undefined;

const poolOptions = computed(() =>
  props.pools
    .filter((pool) => pool.poolId !== 'all')
    .map((pool) => ({
      label: `${pool.poolName} (${pool.total})`,
      value: pool.poolId,
    })),
);

const targetMarket = computed(
  () =>
    props.pools.find((pool) => pool.poolId === targetPoolId.value)?.market ??
    'A股',
);

async function loadCandidates(nextPage = 1) {
  if (!props.open || !targetPoolId.value) return;
  loading.value = true;
  try {
    const result = await getWatchlistStockCandidates(targetPoolId.value, {
      keyword: keyword.value.trim() || undefined,
      market: targetMarket.value,
      pageNum: nextPage,
      pageSize,
    });
    candidates.value = result.rows;
    pageNum.value = nextPage;
    total.value = result.total;
  } catch (error) {
    message.error(error instanceof Error ? error.message : '股票列表加载失败');
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    targetPoolId.value =
      props.defaultPoolId === 'all'
        ? 'my-follow'
        : (props.defaultPoolId ?? 'my-follow');
    keyword.value = '';
    groupName.value = '';
    selectedSymbols.value = [];
    void loadCandidates();
  },
);

watch(targetPoolId, () => {
  selectedSymbols.value = [];
  void loadCandidates();
});

watch(keyword, () => {
  clearTimeout(searchTimer);
  searchTimer = setTimeout(() => void loadCandidates(), 300);
});

onUnmounted(() => clearTimeout(searchTimer));

function toggle(symbol: string, checked: boolean) {
  selectedSymbols.value = checked
    ? [...new Set([...selectedSymbols.value, symbol])]
    : selectedSymbols.value.filter((item) => item !== symbol);
}

async function submit() {
  if (selectedSymbols.value.length === 0 || !targetPoolId.value) return;
  saving.value = true;
  try {
    const result = await addWatchlistStocksBatch(targetPoolId.value, {
      groupName: groupName.value.trim() || undefined,
      symbols: selectedSymbols.value,
    });
    if (result.addedSymbols.length > 0) {
      message.success(`已添加 ${result.addedSymbols.length} 只股票`);
      emit('update:open', false);
      emit('changed', {
        addedSymbols: result.addedSymbols,
        poolCode: result.poolCode,
      });
      return;
    }
    message.info('所选股票已在目标股票池中');
    await loadCandidates(pageNum.value);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '添加股票失败');
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Drawer
    :open="open"
    placement="right"
    title="添加股票"
    :width="500"
    @close="emit('update:open', false)"
  >
    <div class="picker-controls">
      <Select
        v-model:value="targetPoolId"
        :options="poolOptions"
        placeholder="加入到股票池"
      />
      <Input
        v-model:value="groupName"
        :maxlength="64"
        placeholder="分组（可选）"
      />
      <Input
        v-model:value="keyword"
        allow-clear
        placeholder="搜索全部 A 股代码 / 名称"
      >
        <template #prefix><Search class="search-icon" /></template>
      </Input>
    </div>

    <div class="selection-summary">
      <span>共 {{ total }} 只真实股票</span>
      <strong>已选 {{ selectedSymbols.length }}</strong>
    </div>

    <Spin :spinning="loading">
      <div v-if="candidates.length" class="stock-list">
        <label
          v-for="stock in candidates"
          :key="stock.symbol"
          class="stock-row"
          :class="[{ disabled: stock.inPool }]"
        >
          <Checkbox
            :checked="stock.inPool || selectedSymbols.includes(stock.symbol)"
            :disabled="stock.inPool"
            @change="(event) => toggle(stock.symbol, event.target.checked)"
          />
          <span class="stock-code">{{ stock.symbol }}</span>
          <span class="stock-name">{{ stock.name ?? '--' }}</span>
          <small>{{
            stock.inPool ? '已添加' : (stock.industry ?? '未分类')
          }}</small>
        </label>
      </div>
      <Empty v-else description="没有匹配的真实股票" />
    </Spin>

    <Pagination
      v-if="total > pageSize"
      class="candidate-pagination"
      :current="pageNum"
      :page-size="pageSize"
      :show-size-changer="false"
      :total="total"
      size="small"
      @change="loadCandidates"
    />

    <template #footer>
      <div class="drawer-footer">
        <Button @click="emit('update:open', false)">取消</Button>
        <Button
          :disabled="!selectedSymbols.length"
          :loading="saving"
          type="primary"
          @click="submit"
        >
          确认添加
        </Button>
      </div>
    </template>
  </Drawer>
</template>

<style scoped>
.picker-controls {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.picker-controls > :last-child {
  grid-column: 1 / -1;
}

.search-icon {
  width: 15px;
  height: 15px;
}

.selection-summary {
  display: flex;
  justify-content: space-between;
  margin: 18px 0 8px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.selection-summary strong {
  color: #1677ff;
}

.stock-list {
  max-height: calc(100vh - 380px);
  overflow-y: auto;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.stock-row {
  display: grid;
  grid-template-columns: 24px 96px minmax(100px, 1fr) 72px;
  align-items: center;
  min-height: 44px;
  padding: 0 10px;
  cursor: pointer;
  border-bottom: 1px solid hsl(var(--border));
}

.stock-row:last-child {
  border-bottom: 0;
}

.stock-row:hover {
  background: hsl(var(--muted) / 45%);
}

.stock-row.disabled {
  cursor: default;
  opacity: 0.58;
}

.stock-code {
  font-size: 12px;
  color: #1677ff;
}

.stock-name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  white-space: nowrap;
}

.stock-row small {
  color: hsl(var(--muted-foreground));
  text-align: right;
}

.candidate-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.drawer-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
