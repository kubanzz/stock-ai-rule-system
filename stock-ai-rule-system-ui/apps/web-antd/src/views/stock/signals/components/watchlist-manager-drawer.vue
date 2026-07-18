<script lang="ts" setup>
import type { WatchlistPool } from '#/api/stock';

import { computed, reactive, ref, watch } from 'vue';

import { Plus, UserRoundPen } from '@vben/icons';

import {
  Button,
  Drawer,
  Empty,
  Form,
  Input,
  List,
  ListItem,
  message,
  Popconfirm,
  Space,
  Tag,
} from 'ant-design-vue';

import { createWatchlist, deleteWatchlist, updateWatchlist } from '#/api/stock';

const props = defineProps<{
  market: string;
  open: boolean;
  pools: WatchlistPool[];
}>();

const emit = defineEmits<{
  changed: [];
  'update:open': [open: boolean];
}>();

const saving = ref(false);
const editingPoolId = ref<string>();
const form = reactive({ market: props.market, poolName: '' });

const manageablePools = computed(() =>
  props.pools.filter((pool) => pool.poolId !== 'all'),
);

watch(
  () => props.open,
  (open) => {
    if (!open) return;
    editingPoolId.value = undefined;
    form.market = props.market;
    form.poolName = '';
  },
);

function editPool(pool: WatchlistPool) {
  editingPoolId.value = pool.poolId;
  form.market = pool.market;
  form.poolName = pool.poolName;
}

function resetForm() {
  editingPoolId.value = undefined;
  form.market = props.market;
  form.poolName = '';
}

async function submit() {
  if (!form.poolName.trim()) return;
  saving.value = true;
  try {
    if (editingPoolId.value) {
      await updateWatchlist(editingPoolId.value, {
        market: form.market,
        poolName: form.poolName.trim(),
      });
      message.success('股票池已更新');
    } else {
      await createWatchlist({
        market: form.market,
        poolName: form.poolName.trim(),
      });
      message.success('股票池已创建');
    }
    resetForm();
    emit('changed');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '股票池保存失败');
  } finally {
    saving.value = false;
  }
}

async function remove(poolId: string) {
  try {
    await deleteWatchlist(poolId);
    message.success('股票池已删除');
    emit('changed');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '股票池删除失败');
  }
}
</script>

<template>
  <Drawer
    :open="open"
    placement="right"
    title="管理股票池"
    :width="420"
    @close="emit('update:open', false)"
  >
    <Form class="pool-form" layout="vertical">
      <Form.Item :label="editingPoolId ? '编辑股票池' : '新建股票池'">
        <Input
          v-model:value="form.poolName"
          :maxlength="128"
          placeholder="股票池名称"
        />
      </Form.Item>
      <Space>
        <Button :loading="saving" type="primary" @click="submit">
          <UserRoundPen v-if="editingPoolId" class="button-icon" />
          <Plus v-else class="button-icon" />
          {{ editingPoolId ? '保存修改' : '创建股票池' }}
        </Button>
        <Button v-if="editingPoolId" @click="resetForm">取消编辑</Button>
      </Space>
    </Form>

    <div class="list-heading">
      <span>现有股票池</span><Tag>{{ market }}</Tag>
    </div>
    <List
      v-if="manageablePools.length"
      :data-source="manageablePools"
      item-layout="horizontal"
    >
      <template #renderItem="{ item }">
        <ListItem>
          <template #actions>
            <Button type="link" @click="editPool(item)">编辑</Button>
            <Popconfirm
              v-if="item.poolId !== 'my-follow'"
              title="确认删除该股票池？"
              @confirm="remove(item.poolId)"
            >
              <Button danger type="link">删除</Button>
            </Popconfirm>
          </template>
          <div class="pool-item">
            <strong>{{ item.poolName }}</strong>
            <span>{{ item.total }} 只股票</span>
          </div>
        </ListItem>
      </template>
    </List>
    <Empty v-else description="暂无股票池" />
  </Drawer>
</template>

<style scoped>
.pool-form {
  padding-bottom: 18px;
  border-bottom: 1px solid hsl(var(--border));
}

.button-icon {
  width: 15px;
  height: 15px;
  margin-right: 6px;
}

.list-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 18px 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.pool-item {
  display: grid;
  gap: 3px;
}

.pool-item span {
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}
</style>
