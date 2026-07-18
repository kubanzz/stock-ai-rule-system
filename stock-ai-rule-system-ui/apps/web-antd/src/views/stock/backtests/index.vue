<script lang="ts" setup>
import type { BacktestReportOverview } from '#/api/stock';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import { getBacktestReports } from '#/api/stock';

const loading = ref(false);
const report = ref<BacktestReportOverview>();

const filters = reactive({
  market: 'A股',
  objectCode: 'R_TREND_BREAKOUT_001',
  range: ['2024-01-01', '2026-06-20'] as [string, string],
});

async function loadReport() {
  loading.value = true;
  try {
    report.value = await getBacktestReports({
      market: filters.market,
      objectCode: filters.objectCode,
    });
  } finally {
    loading.value = false;
  }
}

onMounted(loadReport);
</script>

<template>
  <Page
    description="验证规则与策略在历史数据中的表现和稳定性。"
    title="回测报告"
  >
    <Alert
      v-if="report?.riskDisclaimer"
      :message="report.riskDisclaimer"
      class="mb-4"
      show-icon
      type="warning"
    />

    <Card class="mb-4" size="small">
      <Form layout="inline">
        <Form.Item label="回测对象">
          <Input v-model:value="filters.objectCode" class="w-56" />
        </Form.Item>
        <Form.Item label="股票池">
          <Select
            v-model:value="filters.market"
            :options="[
              { label: 'A股全市场', value: 'A股' },
              { label: '港股', value: '港股' },
              { label: '美股', value: '美股' },
            ]"
            class="w-36"
          />
        </Form.Item>
        <Form.Item label="回测区间">
          <DatePicker.RangePicker
            v-model:value="filters.range"
            value-format="YYYY-MM-DD"
          />
        </Form.Item>
        <Form.Item>
          <Button :loading="loading" type="primary" @click="loadReport">
            开始回测
          </Button>
        </Form.Item>
      </Form>
    </Card>

    <Row :gutter="[16, 16]" class="mb-4">
      <Col
        v-for="metric in report?.metrics ?? []"
        :key="metric.label"
        :lg="6"
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

    <Row :gutter="[16, 16]" class="mb-4">
      <Col :lg="15" :xs="24">
        <Card size="small" title="累计收益曲线">
          <div class="flex h-64 items-end gap-2">
            <div
              v-for="point in report?.cumulativeReturns ?? []"
              :key="point.date"
              class="flex flex-1 flex-col items-center gap-2"
            >
              <div
                class="w-full rounded bg-blue-500"
                :style="{ height: `${Math.max(12, point.value * 3)}px` }"
              ></div>
              <span class="text-xs text-gray-500">{{
                point.date.slice(2, 7)
              }}</span>
            </div>
          </div>
        </Card>
      </Col>
      <Col :lg="9" :xs="24">
        <Card size="small" title="规则对比">
          <Table
            :columns="[
              { title: '指标', dataIndex: 'metric' },
              { title: '当前规则', dataIndex: 'currentValue' },
              { title: '候选规则', dataIndex: 'candidateValue' },
            ]"
            :data-source="report?.comparison ?? []"
            :pagination="false"
            row-key="metric"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'candidateValue'">
                <Space>
                  {{ record.candidateValue }}
                  <Tag color="green">较优</Tag>
                </Space>
              </template>
            </template>
          </Table>
          <Typography.Paragraph class="mb-0 mt-3 text-xs" type="secondary">
            候选规则必须经过样本内、样本外回测和人工审核后才能上线。
          </Typography.Paragraph>
        </Card>
      </Col>
    </Row>

    <Row :gutter="[16, 16]" class="mb-4">
      <Col :lg="8" :xs="24">
        <Card size="small" title="年度表现">
          <Progress :percent="63" />
          <Progress :percent="49" status="active" />
          <Progress :percent="38" status="exception" />
        </Card>
      </Col>
      <Col :lg="8" :xs="24">
        <Card size="small" title="市场环境表现">
          <Progress :percent="63" />
          <Progress :percent="57" />
          <Progress :percent="49" status="exception" />
        </Card>
      </Col>
      <Col :lg="8" :xs="24">
        <Card size="small" title="行业表现">
          <Progress :percent="73" />
          <Progress :percent="58" />
          <Progress :percent="35" status="exception" />
        </Card>
      </Col>
    </Row>

    <Card size="small" title="失败样本">
      <Table
        :columns="[
          { title: '日期', dataIndex: 'date' },
          { title: '股票', dataIndex: 'symbol' },
          { title: '预测信号', dataIndex: 'signal' },
          { title: '实际收益', dataIndex: 'actualReturn' },
          { title: '原因归类', dataIndex: 'reasonCategory' },
          { title: '相关规则', dataIndex: 'relatedRule' },
        ]"
        :data-source="report?.failureSamples ?? []"
        :pagination="{ pageSize: 5 }"
        row-key="date"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'actualReturn'">
            <span class="text-red-500">{{ record.actualReturn }}%</span>
          </template>
          <template v-else-if="column.dataIndex === 'signal'">
            <Tag color="red">{{ record.signal }}</Tag>
          </template>
        </template>
      </Table>
    </Card>
  </Page>
</template>
