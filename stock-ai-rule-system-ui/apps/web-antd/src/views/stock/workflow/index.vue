<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type {
  DailyWorkflowDependency,
  DailyWorkflowRunResult,
  DailyWorkflowStepResult,
  DailyWorkflowTriggerRequest,
} from '#/api/stock';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  message,
  Switch,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import { getIntegrationDependencies, runDailyWorkflow } from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const loading = ref(false);
const dependencyLoading = ref(false);
const workflowResult = ref<DailyWorkflowRunResult>();
const dependencies = ref<DailyWorkflowDependency[]>([]);

const formState = reactive({
  dryRun: true,
  symbols: 'AAPL,MSFT',
  tradeDate: '',
});

const stepColumns: TableColumnsType<DailyWorkflowStepResult> = [
  { dataIndex: 'stepName', key: 'stepName', title: '步骤', width: 150 },
  { dataIndex: 'stepCode', key: 'stepCode', title: '编码', width: 180 },
  { dataIndex: 'status', key: 'status', title: '状态', width: 100 },
  { dataIndex: 'message', key: 'message', title: '说明' },
  { dataIndex: 'startedAt', key: 'startedAt', title: '开始时间', width: 180 },
  { dataIndex: 'finishedAt', key: 'finishedAt', title: '结束时间', width: 180 },
];

const dependencyColumns: TableColumnsType<DailyWorkflowDependency> = [
  { dataIndex: 'moduleName', key: 'moduleName', title: '模块', width: 140 },
  { dataIndex: 'moduleCode', key: 'moduleCode', title: '编码', width: 150 },
  { dataIndex: 'requiredCapability', key: 'requiredCapability', title: '能力' },
  { dataIndex: 'mergeRisk', key: 'mergeRisk', title: '联调风险' },
];

const statusMeta: Record<string, { color: string; label: string }> = {
  failed: { color: 'red', label: '失败' },
  pending: { color: 'gold', label: '等待' },
  running: { color: 'blue', label: '运行中' },
  skipped: { color: 'default', label: '跳过' },
  success: { color: 'green', label: '成功' },
};

function buildRequest(): DailyWorkflowTriggerRequest {
  return {
    dryRun: formState.dryRun,
    symbols: formState.symbols
      .split(',')
      .map((item) => item.trim().toUpperCase())
      .filter(Boolean),
    tradeDate: formState.tradeDate || undefined,
  };
}

async function loadDependencies() {
  dependencyLoading.value = true;
  try {
    dependencies.value = await getIntegrationDependencies();
  } finally {
    dependencyLoading.value = false;
  }
}

async function triggerWorkflow() {
  loading.value = true;
  try {
    workflowResult.value = await runDailyWorkflow(buildRequest());
    if (workflowResult.value.integrationDependencies?.length) {
      dependencies.value = workflowResult.value.integrationDependencies;
    }
    message.success('每日工作流已运行，输出仅作为辅助决策信号');
  } catch (error) {
    message.error(
      error instanceof Error ? error.message : '每日工作流运行失败',
    );
  } finally {
    loading.value = false;
  }
}

onMounted(loadDependencies);
</script>

<template>
  <Page
    description="手动触发数据采集、因子计算、规则推理、信号输出、回测验证和 AI 复盘流程。"
    title="调度运行"
  >
    <RiskAlert />

    <Card class="mb-4" size="small" title="手动运行">
      <Form :model="formState" layout="inline">
        <Form.Item label="交易日">
          <Input v-model:value="formState.tradeDate" placeholder="2026-06-20" />
        </Form.Item>
        <Form.Item label="标的列表">
          <Input
            v-model:value="formState.symbols"
            class="w-64"
            placeholder="AAPL,MSFT"
          />
        </Form.Item>
        <Form.Item label="试运行">
          <Switch v-model:checked="formState.dryRun" />
        </Form.Item>
        <Form.Item>
          <Button :loading="loading" type="primary" @click="triggerWorkflow">
            运行工作流
          </Button>
        </Form.Item>
      </Form>
    </Card>

    <Card v-if="workflowResult" class="mb-4" size="small" title="运行摘要">
      <Descriptions bordered size="small">
        <Descriptions.Item label="运行 ID">
          {{ workflowResult.runId }}
        </Descriptions.Item>
        <Descriptions.Item label="交易日">
          {{ workflowResult.tradeDate || '-' }}
        </Descriptions.Item>
        <Descriptions.Item label="状态">
          <Tag :color="statusMeta[workflowResult.status]?.color">
            {{
              statusMeta[workflowResult.status]?.label || workflowResult.status
            }}
          </Tag>
        </Descriptions.Item>
        <Descriptions.Item label="标的">
          {{ workflowResult.symbols?.join(', ') || '-' }}
        </Descriptions.Item>
        <Descriptions.Item label="开始时间">
          {{ workflowResult.startedAt || '-' }}
        </Descriptions.Item>
        <Descriptions.Item label="结束时间">
          {{ workflowResult.finishedAt || '-' }}
        </Descriptions.Item>
      </Descriptions>
    </Card>

    <Card class="mb-4" size="small" title="步骤结果">
      <Table
        :columns="stepColumns"
        :data-source="workflowResult?.steps ?? []"
        :loading="loading"
        row-key="stepCode"
        size="small"
      >
        <template #emptyText>
          <Typography.Text type="secondary">
            暂无步骤结果，请先运行每日工作流。
          </Typography.Text>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="statusMeta[record.status]?.color">
              {{ statusMeta[record.status]?.label || record.status }}
            </Tag>
          </template>
        </template>
      </Table>
    </Card>

    <Card size="small" title="集成依赖">
      <Table
        :columns="dependencyColumns"
        :data-source="dependencies"
        :loading="dependencyLoading"
        row-key="moduleCode"
        size="small"
      />
    </Card>
  </Page>
</template>
