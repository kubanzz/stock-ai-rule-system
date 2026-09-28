<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { SignalType, StockResearchDetail } from '#/api/stock';

import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
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

import { getStockResearchDetail, getWatchlists } from '#/api/stock';

const route = useRoute();
const router = useRouter();
const loading = ref(false);
const loadError = ref<string>();
const detail = ref<StockResearchDetail>();
const priceChartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(priceChartRef);
let loadSequence = 0;

const signalMeta: Record<SignalType, { color: string; label: string }> = {
  bearish: { color: 'red', label: '看跌' },
  bullish: { color: 'green', label: '看涨' },
  high_risk: { color: 'volcano', label: '高风险' },
  watch: { color: 'gold', label: '观望' },
};

const symbol = computed(() =>
  String(route.params.symbol || route.query.symbol || ''),
);
const analysisDate = computed(() =>
  typeof route.query.date === 'string' ? route.query.date : undefined,
);

const priceSeries = computed(() => detail.value?.priceSeries ?? []);
const priceRange = computed(() => {
  const values = priceSeries.value.map((point) => point.close);
  if (values.length === 0) return { max: 0, min: 0 };
  return { max: Math.max(...values), min: Math.min(...values) };
});

const latestPrice = computed(() => priceSeries.value.at(-1)?.close ?? 0);
const previousPrice = computed(() => priceSeries.value.at(-2)?.close ?? latestPrice.value);
const latestChange = computed(() =>
  previousPrice.value
    ? ((latestPrice.value - previousPrice.value) / previousPrice.value) * 100
    : 0,
);
const latestDate = computed(() => priceSeries.value.at(-1)?.date ?? detail.value?.tradeDate ?? '-');
const priceRangeLabel = computed(() =>
  priceSeries.value.length > 1
    ? `${priceSeries.value[0]?.date.slice(5)} — ${latestDate.value.slice(5)}`
    : '暂无足够数据',
);

function renderPriceChart() {
  const points = priceSeries.value;
  if (points.length === 0) return;
  nextTick(() => {
    renderEcharts({
      animationDuration: 500,
      dataZoom: [{ end: 100, start: 0, type: 'inside' }],
      grid: { bottom: 40, containLabel: true, left: 44, right: 18, top: 22 },
      series: [
        {
          areaStyle: {
            color: {
              colorStops: [
                { color: 'rgba(37, 99, 235, 0.22)', offset: 0 },
                { color: 'rgba(37, 99, 235, 0.02)', offset: 1 },
              ],
              type: 'linear',
              x: 0,
              x2: 0,
              y: 0,
              y2: 1,
            },
          },
          data: points.map((point) => point.close),
          emphasis: { focus: 'series' },
          itemStyle: { color: '#2563eb' },
          lineStyle: { color: '#2563eb', width: 2.5 },
          markPoint: {
            data: [
              { name: '最高', type: 'max' },
              { name: '最低', type: 'min' },
            ],
            label: { color: '#334155', fontSize: 10 },
            symbolSize: 32,
          },
          showSymbol: false,
          smooth: 0.2,
          type: 'line',
        },
        {
          barMaxWidth: 9,
          data: points.map((point, index) => ({
            itemStyle: {
              color:
                index > 0 && point.close >= points[index - 1]!.close
                  ? 'rgba(34, 197, 94, 0.38)'
                  : 'rgba(239, 68, 68, 0.38)',
            },
            value: point.volume ?? 0,
          })),
          name: '成交量',
          type: 'bar',
          yAxisIndex: 1,
        },
      ],
      tooltip: {
        axisPointer: { type: 'cross' },
        trigger: 'axis',
        valueFormatter: (value: unknown) =>
          typeof value === 'number' ? value.toFixed(2) : String(value ?? '-'),
      },
      xAxis: {
        axisLabel: { color: '#64748b', fontSize: 11, formatter: (value: string) => value.slice(5) },
        axisLine: { lineStyle: { color: '#cbd5e1' } },
        boundaryGap: false,
        data: points.map((point) => point.date),
        axisTick: { show: false },
        type: 'category',
      },
      yAxis: [
        {
          axisLabel: { color: '#64748b', fontSize: 11, formatter: (value: number) => value.toFixed(2) },
          axisLine: { show: false },
          scale: true,
          splitLine: { lineStyle: { color: 'rgba(148, 163, 184, 0.2)' } },
          type: 'value',
        },
        { max: (value: { max: number }) => value.max * 4, show: false, type: 'value' },
      ],
    });
  });
}

async function loadDetail() {
  const sequence = ++loadSequence;
  if (!symbol.value) {
    detail.value = undefined;
    loadError.value = undefined;
    loading.value = false;
    return;
  }
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

async function openDefaultWatchlistStock() {
  if (symbol.value) return;
  try {
    const pools = await getWatchlists({ market: 'A股' });
    const followPool = pools.find((pool) => pool.poolId === 'my-follow');
    const firstStock = followPool?.stocks?.[0]?.symbol ?? pools
      .flatMap((pool) => pool.stocks ?? [])
      .find((stock) => stock.symbol)?.symbol;
    if (firstStock) {
      await router.replace({ name: 'StockDetail', params: { symbol: firstStock } });
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '关注列表加载失败';
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
watch(priceSeries, renderPriceChart, { deep: true });
openDefaultWatchlistStock();
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
      <Empty
        v-if="!detail && !loading && !loadError"
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="请选择关注列表中的股票查看研究数据。"
      />
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
            <Card class="price-card" size="small">
              <template #title>
                <div class="chart-title-row">
                  <div>
                    <span>价格走势</span>
                    <small>{{ priceRangeLabel }} · {{ priceSeries.length }} 个交易日</small>
                  </div>
                  <Tag :color="latestChange >= 0 ? 'green' : 'red'">
                    {{ latestChange >= 0 ? '+' : '' }}{{ latestChange.toFixed(2) }}%
                  </Tag>
                </div>
              </template>
              <div v-if="priceSeries.length" class="price-chart">
                <div class="chart-summary">
                  <div>
                    <span class="summary-label">最新收盘</span>
                    <strong>{{ latestPrice.toFixed(2) }}</strong>
                    <em :class="latestChange >= 0 ? 'positive' : 'negative'">
                      {{ latestChange >= 0 ? '+' : '' }}{{ latestChange.toFixed(2) }}%
                    </em>
                  </div>
                  <div class="summary-range">
                    <span>区间最低 <b>{{ priceRange.min.toFixed(2) }}</b></span>
                    <span>区间最高 <b>{{ priceRange.max.toFixed(2) }}</b></span>
                    <span>更新于 {{ latestDate }}</span>
                  </div>
                </div>
                <EchartsUI
                  ref="priceChartRef"
                  aria-label="最近行情收盘价和成交量走势"
                  class="price-chart-canvas"
                />
              </div>
              <Typography.Text v-else type="secondary">
                暂无行情数据，页面打开时会自动尝试补齐最新交易日。
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
              <List v-if="detail.factors?.length" :data-source="detail.factors" size="small">
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
              <Empty v-else :image="Empty.PRESENTED_IMAGE_SIMPLE" description="当前交易日尚未生成因子数据">
                <template #footer>
                  <Typography.Paragraph type="secondary" class="mb-2">
                    请运行每日工作流完成行情同步和因子计算后再查看。
                  </Typography.Paragraph>
                  <Button type="primary" @click="router.push('/stock/workflow')">
                    去运行工作流
                  </Button>
                </template>
              </Empty>
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
  padding: 4px 4px 0;
}

.price-chart-canvas {
  width: 100%;
  height: 290px;
}

.chart-title-row,
.chart-summary,
.summary-range {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chart-title-row small {
  display: block;
  margin-top: 4px;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 400;
}

.chart-summary {
  padding: 4px 0 2px;
}

.summary-label,
.summary-range {
  color: #64748b;
  font-size: 12px;
}

.chart-summary strong {
  display: inline-block;
  margin: 0 10px 0 8px;
  color: #0f172a;
  font-size: 24px;
  letter-spacing: -0.03em;
}

.chart-summary em {
  font-size: 13px;
  font-style: normal;
  font-weight: 600;
}

.summary-range {
  flex-wrap: wrap;
  gap: 4px 14px;
  justify-content: flex-end;
}

.summary-range b {
  color: #334155;
  font-weight: 600;
}

.positive { color: #16a34a; }
.negative { color: #dc2626; }
</style>
