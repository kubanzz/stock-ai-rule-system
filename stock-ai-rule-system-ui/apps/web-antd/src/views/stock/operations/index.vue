<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type { MarketDataSyncRequest, MarketDataSyncRun } from '#/api/stock';

import { reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Form,
  Input,
  message,
  Space,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import { syncMarketData } from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const loading = ref(false);
const rows = ref<MarketDataSyncRun[]>([]);

const formState = reactive<MarketDataSyncRequest>({
  endDate: '',
  startDate: '',
  targetSymbol: '',
  triggerBy: 'operator',
  triggerType: 'manual',
});

const columns: TableColumnsType<MarketDataSyncRun> = [
  { dataIndex: 'runId', key: 'runId', title: '运行 ID', width: 150 },
  { dataIndex: 'syncType', key: 'syncType', title: '同步类型', width: 120 },
  { dataIndex: 'targetSymbol', key: 'targetSymbol', title: '标的', width: 110 },
  { dataIndex: 'status', key: 'status', title: '状态', width: 100 },
  { dataIndex: 'scanned', key: 'scanned', title: '扫描', width: 90 },
  { dataIndex: 'inserted', key: 'inserted', title: '新增', width: 90 },
  { dataIndex: 'updated', key: 'updated', title: '更新', width: 90 },
  { dataIndex: 'failed', key: 'failed', title: '失败', width: 90 },
  { dataIndex: 'startedAt', key: 'startedAt', title: '开始时间', width: 180 },
  { dataIndex: 'finishedAt', key: 'finishedAt', title: '结束时间', width: 180 },
  { dataIndex: 'errors', key: 'errors', title: '异常摘要' },
];

const statusMeta: Record<string, { color: string; label: string }> = {
  failed: { color: 'red', label: '失败' },
  pending: { color: 'gold', label: '等待' },
  running: { color: 'blue', label: '运行中' },
  skipped: { color: 'default', label: '跳过' },
  success: { color: 'green', label: '成功' },
};

function cleanRequest(): MarketDataSyncRequest {
  const symbol = formState.targetSymbol?.trim().toUpperCase();
  return {
    endDate: formState.endDate || undefined,
    startDate: formState.startDate || undefined,
    targetSymbol: symbol || undefined,
    triggerBy: formState.triggerBy || undefined,
    triggerType: formState.triggerType || 'manual',
  };
}

async function runSync() {
  loading.value = true;
  try {
    const result = await syncMarketData(cleanRequest());
    rows.value.unshift(result);
    message.success('行情同步已完成，后续信号仍需结合规则与回测复核');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '行情同步失败');
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <Page
    description="手动触发行情基础数据同步，并查看本次运行的导入摘要。"
    title="运营控制台"
  >
    <RiskAlert />

    <Card class="mb-4" size="small" title="行情同步">
      <Form :model="formState" layout="inline">
        <Form.Item label="标的">
          <Input
            v-model:value="formState.targetSymbol"
            allow-clear
            placeholder="AAPL"
          />
        </Form.Item>
        <Form.Item label="开始日期">
          <Input v-model:value="formState.startDate" placeholder="2026-06-01" />
        </Form.Item>
        <Form.Item label="结束日期">
          <Input v-model:value="formState.endDate" placeholder="2026-06-20" />
        </Form.Item>
        <Form.Item label="触发人">
          <Input v-model:value="formState.triggerBy" placeholder="operator" />
        </Form.Item>
        <Form.Item label="触发方式">
          <Input v-model:value="formState.triggerType" placeholder="manual" />
        </Form.Item>
        <Form.Item>
          <Button :loading="loading" type="primary" @click="runSync">
            同步行情
          </Button>
        </Form.Item>
      </Form>
    </Card>

    <Card size="small" title="同步结果">
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="runId"
        size="small"
      >
        <template #emptyText>
          <Typography.Text type="secondary">
            暂无同步记录，请先触发一次行情同步。
          </Typography.Text>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="statusMeta[record.status]?.color">
              {{ statusMeta[record.status]?.label || record.status }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'errors'">
            <Space
              v-if="record.errors?.length"
              direction="vertical"
              size="small"
            >
              <Typography.Text
                v-for="error in record.errors"
                :key="error"
                type="danger"
              >
                {{ error }}
              </Typography.Text>
            </Space>
            <Typography.Text v-else type="secondary">-</Typography.Text>
          </template>
        </template>
      </Table>
    </Card>
  </Page>
</template>
