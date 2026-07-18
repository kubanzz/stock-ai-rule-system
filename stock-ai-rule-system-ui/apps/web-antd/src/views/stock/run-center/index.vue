<script lang="ts" setup>
import type { RunCenterOverview } from '#/api/stock';

import { onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Col,
  DatePicker,
  Row,
  Space,
  Statistic,
  Steps,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import {
  getRunCenterOverview,
  runDailyWorkflow,
  syncMarketData,
} from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const loading = ref(false);
const running = ref(false);
const tradeDate = ref('2026-06-20');
const overview = ref<RunCenterOverview>();

async function loadOverview() {
  loading.value = true;
  try {
    overview.value = await getRunCenterOverview(tradeDate.value);
  } finally {
    loading.value = false;
  }
}

async function triggerWorkflow() {
  running.value = true;
  try {
    await runDailyWorkflow({ dryRun: false, tradeDate: tradeDate.value });
    await loadOverview();
  } finally {
    running.value = false;
  }
}

async function triggerSync() {
  running.value = true;
  try {
    await syncMarketData({
      endDate: tradeDate.value,
      startDate: tradeDate.value,
      triggerBy: 'operator',
      triggerType: 'manual',
    });
    await loadOverview();
  } finally {
    running.value = false;
  }
}

function stepStatus(status: string) {
  if (status === 'success') return 'finish';
  if (status === 'running') return 'process';
  if (status === 'failed') return 'error';
  return 'wait';
}

onMounted(loadOverview);
</script>

<template>
  <Page
    description="集中查看行情同步、因子计算、信号生成、回测和 AI 复盘运行状态。"
    title="运行中心"
  >
    <RiskAlert />

    <div class="mb-4 flex justify-between">
      <Space>
        <DatePicker v-model:value="tradeDate" value-format="YYYY-MM-DD" />
        <Button :loading="loading" @click="loadOverview">刷新</Button>
      </Space>
      <Space>
        <Button :loading="running" @click="triggerSync">同步行情</Button>
        <Button :loading="running" type="primary" @click="triggerWorkflow">
          运行每日工作流
        </Button>
      </Space>
    </div>

    <Row :gutter="[16, 16]" class="mb-4">
      <Col
        v-for="metric in overview?.metrics ?? []"
        :key="metric.label"
        :lg="8"
        :sm="12"
        :xs="24"
      >
        <Card size="small">
          <Statistic
            :suffix="metric.unit"
            :title="metric.label"
            :value="metric.value"
          />
        </Card>
      </Col>
    </Row>

    <Row :gutter="[16, 16]">
      <Col :lg="10" :xs="24">
        <Card size="small" title="运行状态">
          <Space align="center">
            <Typography.Text type="secondary">服务状态</Typography.Text>
            <Tag
              :color="overview?.serviceStatus === 'failed' ? 'red' : 'green'"
            >
              {{ overview?.serviceStatus ?? 'normal' }}
            </Tag>
          </Space>
          <Steps
            :current="overview?.steps.length ?? 0"
            :items="
              (overview?.steps ?? []).map((step) => ({
                description: step.message,
                status: stepStatus(step.status),
                title: step.stepName,
              }))
            "
            class="mt-4"
            direction="vertical"
            size="small"
          />
        </Card>
      </Col>
      <Col :lg="14" :xs="24">
        <Card size="small" title="任务明细">
          <Table
            :columns="[
              { title: '步骤', dataIndex: 'stepName' },
              { title: '状态', dataIndex: 'status' },
              { title: '开始时间', dataIndex: 'startedAt' },
              { title: '结束时间', dataIndex: 'finishedAt' },
              { title: '说明', dataIndex: 'message' },
            ]"
            :data-source="overview?.steps ?? []"
            :loading="loading"
            :pagination="false"
            row-key="stepCode"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'status'">
                <Tag :color="record.status === 'failed' ? 'red' : 'green'">
                  {{ record.status }}
                </Tag>
              </template>
            </template>
          </Table>
        </Card>
      </Col>
    </Row>
  </Page>
</template>
