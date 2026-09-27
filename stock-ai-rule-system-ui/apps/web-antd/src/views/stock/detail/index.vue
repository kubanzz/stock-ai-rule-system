<script lang="ts" setup>
import type { SignalType, StockResearchDetail } from '#/api/stock';

import { computed, ref, watch } from 'vue';
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
const loadError = ref<string>();
const detail = ref<StockResearchDetail>();
let loadSequence = 0;

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

const priceBounds = computed(() => {
  const values = detail.value?.priceSeries.map((point) => point.close) ?? [];
  if (values.length === 0) return { max: 1, min: 0 };
  const min = Math.min(...values);
  const max = Math.max(...values);
  const padding = Math.max((max - min) * 0.1, max * 0.01, 0.01);
  return { max: max + padding, min: Math.max(0, min - padding) };
});

const priceRange = computed(() => {
  const values = detail.value?.priceSeries.map((point) => point.close) ?? [];
  if (values.length === 0) return { max: 0, min: 0 };
  return { max: Math.max(...values), min: Math.min(...values) };
});

const priceChartPoints = computed(() => {
  const points = detail.value?.priceSeries ?? [];
  const { max, min } = priceBounds.value;
  const span = max - min || 1;
  return points
    .map((point, index) => {
      const x = points.length === 1 ? 50 : (index / (points.length - 1)) * 100;
      const y = 92 - ((point.close - min) / span) * 82;
      return { ...point, x, y };
    });
});

const priceChartPolyline = computed(() =>
  priceChartPoints.value.map((point) => `${point.x},${point.y}`).join(' '),
);

const priceChartArea = computed(() => {
  const points = priceChartPoints.value;
  if (points.length === 0) return '';
  return `0,100 ${points.map((point) => `${point.x},${point.y}`).join(' ')} 100,100`;
});

async function loadDetail() {
  const sequence = ++loadSequence;
  loading.value = true;
  loadError.value = undefined;
  try {
    const result = await getStockResearchDetail(
      symbol.value,
      analysisDate.value,
    );
    if (sequence === loadSequence) detail.value = result;
  } catch (error) {
    if (sequence === loadSequence) {
      detail.value = undefined;
      loadError.value = error instanceof Error ? error.message : '股票详情加载失败';
    }
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}

function signalLabel(signal?: SignalType) {
  return signal ? signalMeta[signal]?.label : '观望';
}

function signalColor(signal?: SignalType) {
  return signal ? signalMeta[signal]?.color : 'gold';
}

watch(() => [route.params.symbol, route.query.date], loadDetail, {
  immediate: true,
});
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
    <Alert
      v-if="loadError"
      :message="loadError"
      class="mb-4"
      show-icon
      type="error"
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
              <div v-if="priceChartPoints.length" class="price-chart">
                <svg
                  aria-label="最近一个月股票收盘价走势"
                  class="price-chart-svg"
                  preserveAspectRatio="none"
                  role="img"
                  viewBox="0 0 100 100"
                >
                  <polygon :points="priceChartArea" class="price-chart-area" />
                  <polyline
                    :points="priceChartPolyline"
                    class="price-chart-line"
                  />
                  <circle
                    v-for="point in priceChartPoints"
                    :key="point.date"
                    :cx="point.x"
                    :cy="point.y"
                    class="price-chart-point"
                    r="1.4"
                  />
                </svg>
                <div class="price-chart-labels">
                  <span>{{ priceChartPoints[0]?.date.slice(5) }}</span>
                  <span>{{ priceChartPoints.at(-1)?.date.slice(5) }}</span>
                </div>
                <div class="price-chart-range">
                  <span>最低 {{ priceRange.min.toFixed(2) }}</span>
                  <span>最高 {{ priceRange.max.toFixed(2) }}</span>
                </div>
              </div>
              <Typography.Text v-else type="secondary">
                暂无最近一个月行情数据，详情打开时会尝试补齐行情。
              </Typography.Text>
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

<style scoped>
.price-chart {
  padding: 8px 4px 0;
}

.price-chart-svg {
  display: block;
  width: 100%;
  height: 220px;
  overflow: visible;
  background: linear-gradient(
    to bottom,
    transparent 24%,
    hsl(var(--border) / 0.55) 25%,
    transparent 26%,
    transparent 49%,
    hsl(var(--border) / 0.55) 50%,
    transparent 51%,
    transparent 74%,
    hsl(var(--border) / 0.55) 75%,
    transparent 76%
  );
}

.price-chart-area {
  fill: #1677ff;
  opacity: 0.12;
}

.price-chart-line {
  fill: none;
  stroke: #1677ff;
  stroke-linecap: round;
  stroke-linejoin: round;
  stroke-width: 0.9;
  vector-effect: non-scaling-stroke;
}

.price-chart-point {
  fill: #fff;
  stroke: #1677ff;
  stroke-width: 0.8;
  vector-effect: non-scaling-stroke;
}

.price-chart-labels,
.price-chart-range {
  display: flex;
  justify-content: space-between;
  color: hsl(var(--muted-foreground));
  font-size: 12px;
}

.price-chart-range {
  margin-top: 8px;
}
</style>
