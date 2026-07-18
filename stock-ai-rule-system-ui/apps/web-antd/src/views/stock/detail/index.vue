<script lang="ts" setup>
import type { SignalType, StockResearchDetail } from '#/api/stock';

import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Card,
  Col,
  List,
  Progress,
  Row,
  Skeleton,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import { getStockResearchDetail } from '#/api/stock';

const route = useRoute();
const loading = ref(false);
const detail = ref<StockResearchDetail>();

const signalMeta: Record<SignalType, { color: string; label: string }> = {
  bearish: { color: 'red', label: '看跌' },
  bullish: { color: 'green', label: '看涨' },
  high_risk: { color: 'volcano', label: '高风险' },
  watch: { color: 'gold', label: '观望' },
};

const symbol = computed(() => String(route.params.symbol || 'AAPL'));
const analysisDate = computed(() =>
  typeof route.query.date === 'string' ? route.query.date : undefined,
);

async function loadDetail() {
  loading.value = true;
  try {
    detail.value = await getStockResearchDetail(
      symbol.value,
      analysisDate.value,
    );
  } finally {
    loading.value = false;
  }
}

function signalLabel(signal?: SignalType) {
  return signal ? signalMeta[signal]?.label : '观望';
}

function signalColor(signal?: SignalType) {
  return signal ? signalMeta[signal]?.color : 'gold';
}

onMounted(loadDetail);
watch(() => [route.params.symbol, route.query.date], loadDetail);
</script>

<template>
  <Page
    :description="`${symbol} 个股行情、因子状态、规则触发链与历史预测表现`"
    title="股票研究"
  >
    <Alert
      v-if="detail?.riskDisclaimer"
      :message="detail.riskDisclaimer"
      class="mb-4"
      show-icon
      type="warning"
    />

    <Skeleton :loading="loading" active>
      <template v-if="detail">
        <Row :gutter="[16, 16]" class="mb-4">
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Typography.Text type="secondary">股票</Typography.Text>
              <Typography.Title :level="4" class="mb-0">
                {{ detail.symbol }}
              </Typography.Title>
              <Typography.Text>{{ detail.name }}</Typography.Text>
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Statistic
                :precision="1"
                :value="detail.confidence"
                suffix="%"
                title="置信度"
              />
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Statistic
                :precision="0"
                :value="detail.riskScore"
                title="风险分"
              />
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Typography.Text type="secondary">当前信号</Typography.Text>
              <div class="mt-2">
                <Tag :color="signalColor(detail.signal)">
                  {{ signalLabel(detail.signal) }}
                </Tag>
              </div>
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Statistic :value="detail.ruleChain.length" title="触发规则数" />
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Typography.Text type="secondary">交易日</Typography.Text>
              <Typography.Title :level="5" class="mb-0 mt-2">
                {{ detail.tradeDate || '-' }}
              </Typography.Title>
            </Card>
          </Col>
        </Row>

        <Row :gutter="[16, 16]" class="mb-4">
          <Col :lg="14" :xs="24">
            <Card size="small" title="价格走势">
              <div class="flex h-56 items-end gap-2">
                <div
                  v-for="point in detail.priceSeries"
                  :key="point.date"
                  class="flex flex-1 flex-col items-center gap-2"
                >
                  <div
                    class="w-full rounded bg-blue-500/70"
                    :style="{ height: `${Math.max(12, point.close / 2)}px` }"
                  ></div>
                  <span class="text-xs text-gray-500">{{
                    point.date.slice(5)
                  }}</span>
                </div>
              </div>
            </Card>
          </Col>
          <Col :lg="10" :xs="24">
            <Card size="small" title="信号解释">
              <Typography.Paragraph>
                {{ detail.explanation }}
              </Typography.Paragraph>
              <Space wrap>
                <Tag
                  v-for="item in detail.ruleChain"
                  :key="item.ruleCode"
                  color="geekblue"
                >
                  {{ item.ruleCode }}
                </Tag>
              </Space>
            </Card>
          </Col>
        </Row>

        <Row :gutter="[16, 16]" class="mb-4">
          <Col :lg="12" :xs="24">
            <Card size="small" title="因子状态">
              <List :data-source="detail.factors" size="small">
                <template #renderItem="{ item }">
                  <List.Item>
                    <List.Item.Meta
                      :description="item.description"
                      :title="item.factor"
                    />
                    <Space direction="vertical" class="w-40">
                      <Tag color="blue">{{ item.status }}</Tag>
                      <Progress
                        :percent="Math.round(item.strength)"
                        size="small"
                      />
                    </Space>
                  </List.Item>
                </template>
              </List>
            </Card>
          </Col>
          <Col :lg="12" :xs="24">
            <Card size="small" title="规则触发链">
              <List :data-source="detail.ruleChain" size="small">
                <template #renderItem="{ item }">
                  <List.Item>
                    <List.Item.Meta
                      :description="item.condition"
                      :title="`${item.ruleCode} · ${item.ruleName}`"
                    />
                    <Statistic :value="item.contribution" prefix="+" />
                  </List.Item>
                </template>
              </List>
            </Card>
          </Col>
        </Row>

        <Card size="small" title="历史预测记录">
          <Table
            :columns="[
              { title: '日期', dataIndex: 'date' },
              { title: '预测信号', dataIndex: 'signal' },
              { title: '预测方向', dataIndex: 'direction' },
              { title: '置信度', dataIndex: 'confidence' },
              { title: '实际收益', dataIndex: 'actualReturn' },
              { title: '是否命中', dataIndex: 'hitStatus' },
            ]"
            :data-source="detail.history"
            :pagination="false"
            row-key="date"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'signal'">
                <Tag :color="signalColor(record.signal)">
                  {{ signalLabel(record.signal) }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'confidence'">
                {{ record.confidence }}%
              </template>
              <template v-else-if="column.dataIndex === 'actualReturn'">
                <span
                  :class="
                    (record.actualReturn ?? 0) >= 0
                      ? 'text-green-500'
                      : 'text-red-500'
                  "
                >
                  {{ record.actualReturn ?? '--' }}%
                </span>
              </template>
            </template>
          </Table>
        </Card>
      </template>
    </Skeleton>
  </Page>
</template>
