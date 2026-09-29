<script lang="ts" setup>
import type {
  BacktestReportOverview,
  BacktestReportResultPayload,
  BacktestRequest,
  WatchlistPool,
} from '#/api/stock';

import { computed, onMounted, reactive, ref, watch } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'ant-design-vue';

import { getBacktestReports, getWatchlists, runBacktest } from '#/api/stock';

const loading = ref(false);
const report = ref<BacktestReportOverview>();
const watchlists = ref<WatchlistPool[]>([]);

const resultPayload = computed<BacktestReportResultPayload>(() => {
  const resultJson = report.value?.resultJson;
  if (!resultJson) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(resultJson);
    return parsed && typeof parsed === 'object'
      ? (parsed as BacktestReportResultPayload)
      : {};
  } catch {
    return {};
  }
});

const reportEquityCurve = computed(() => {
  const curve = report.value?.equityCurve ?? resultPayload.value.equityCurve;
  return Array.isArray(curve) ? curve : [];
});

const equityBars = computed(() => {
  const points = reportEquityCurve.value;
  if (points.length === 0) {
    return [];
  }
  const values = points.map((point) => point.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  return points.map((point) => ({
    ...point,
    height: `${Math.max(18, 24 + ((point.value - min) / range) * 180)}px`,
    returnLabel: `${((point.value - 1) * 100).toFixed(2)}%`,
  }));
});

function metricValue(label: string) {
  return report.value?.metrics.find((metric) => metric.label === label)?.value;
}

const reportSampleCount = computed(
  () =>
    report.value?.sampleCount ??
    resultPayload.value.signalCount ??
    metricValue('触发次数'),
);

const reportEvaluatedCount = computed(
  () =>
    report.value?.evaluatedCount ??
    resultPayload.value.evaluatedCount ??
    resultPayload.value.triggerCount ??
    metricValue('触发次数'),
);

const reportUnevaluableCount = computed(
  () =>
    report.value?.unevaluableCount ??
    resultPayload.value.unevaluableCount ??
    resultPayload.value.skippedCount,
);

const reportStatus = computed(
  () => report.value?.status ?? resultPayload.value.status,
);

function countDisplay(value: number | undefined) {
  return value === undefined ? '--' : value;
}

function statusText(status: string | undefined) {
  if (!status) {
    return '未返回状态';
  }
  return {
    failed: '失败',
    partial: '部分完成',
    pending: '等待执行',
    running: '执行中',
    skipped: '已跳过',
    success: '已完成',
  }[status] ?? status;
}

function statusColor(status: string | undefined) {
  if (status === 'success') {
    return 'green';
  }
  if (status === 'failed') {
    return 'red';
  }
  if (status === 'partial') {
    return 'orange';
  }
  return 'default';
}

const filters = reactive({
  market: 'A股',
  objectCode: 'R_TREND_BREAKOUT_001',
  objectType: 'rule' as BacktestRequest['objectType'],
  range: ['2024-01-01', '2026-06-20'] as [string, string],
  holdingPeriod: 5,
  stockPoolType: 'market' as NonNullable<BacktestRequest['stockPoolType']>,
  stockPoolCode: 'A股',
  symbolsText: '',
});

async function loadReport() {
  report.value = undefined;
  report.value = await getBacktestReports({
    endDate: filters.range[1],
    holdingPeriod: filters.holdingPeriod,
    market: filters.market,
    objectCode: filters.objectCode,
    objectType: filters.objectType,
    startDate: filters.range[0],
    stockPoolCode:
      filters.stockPoolType === 'market'
        ? filters.market
        : filters.stockPoolCode,
    stockPoolType: filters.stockPoolType,
    symbols: parseSymbols(),
  });
}

function parseSymbols() {
  return [
    ...new Set(
      filters.symbolsText
        .split(/[\s,，;；]+/)
        .map((symbol) => symbol.trim().toUpperCase())
        .filter(Boolean),
    ),
  ];
}

async function startBacktest() {
  if (!filters.objectCode.trim()) {
    message.warning('请输入规则编码');
    return;
  }
  if (!filters.range?.[0] || !filters.range?.[1]) {
    message.warning('请选择完整的回测区间');
    return;
  }
  if (filters.stockPoolType === 'watchlist') {
    const selectedPool = watchlists.value.find(
      (pool) => pool.poolId === filters.stockPoolCode && pool.poolId !== 'all',
    );
    if (!selectedPool) {
      message.warning('请选择股票池');
      return;
    }
  }
  const symbols = parseSymbols();
  if (filters.stockPoolType === 'custom' && symbols.length === 0) {
    message.warning('请输入至少一个股票代码');
    return;
  }

  loading.value = true;
  let backtestFinished = false;
  try {
    const payload: BacktestRequest = {
      endDate: filters.range[1],
      holdingPeriod: filters.holdingPeriod,
      objectCode: filters.objectCode.trim(),
      objectType: filters.objectType,
      startDate: filters.range[0],
      stockPoolCode:
        filters.stockPoolType === 'market'
          ? filters.market
          : filters.stockPoolCode,
      stockPoolType: filters.stockPoolType,
      ...(filters.stockPoolType === 'custom' ? { symbols } : {}),
    };
    await runBacktest(payload);
    backtestFinished = true;
    await loadReport();
    if (report.value?.status === 'failed') {
      message.error('回测失败，请检查报告状态和数据覆盖范围');
    } else {
      message.success('回测已完成，报告已刷新');
    }
  } catch (error) {
    message.error(
      backtestFinished
        ? '回测已执行，但报告加载失败，请稍后重新查询'
        : error instanceof Error
          ? error.message
          : '回测执行失败',
    );
  } finally {
    loading.value = false;
  }
}

async function loadPage() {
  loading.value = true;
  try {
    watchlists.value = await getWatchlists({ market: filters.market });
    await loadReport();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '回测页面加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(loadPage);

watch(
  () => filters.stockPoolType,
  (stockPoolType) => {
    if (stockPoolType === 'market') {
      filters.stockPoolCode = filters.market;
      return;
    }
    if (stockPoolType === 'custom') {
      filters.stockPoolCode = 'custom';
      return;
    }
    filters.stockPoolCode =
      watchlists.value.find((pool) => pool.poolId !== 'all')?.poolId ?? '';
  },
);

watch(
  () => filters.market,
  async (market) => {
    if (filters.stockPoolType === 'market') {
      filters.stockPoolCode = market;
    }
    try {
      watchlists.value = await getWatchlists({ market });
      if (
        filters.stockPoolType === 'watchlist' &&
        !watchlists.value.some(
          (pool) => pool.poolId === filters.stockPoolCode && pool.poolId !== 'all',
        )
      ) {
        filters.stockPoolCode =
          watchlists.value.find((pool) => pool.poolId !== 'all')?.poolId ?? '';
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '股票池加载失败');
    }
  },
);
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
        <Form.Item label="对象类型">
          <Select
            v-model:value="filters.objectType"
            :options="[
              { label: '正式规则', value: 'rule' },
              { label: '候选规则', value: 'candidate_rule' },
            ]"
            class="w-32"
          />
        </Form.Item>
        <Form.Item label="股票池范围">
          <Select
            v-model:value="filters.stockPoolType"
            :options="[
              { label: '市场股票池', value: 'market' },
              { label: '我的股票池', value: 'watchlist' },
              { label: '自定义股票', value: 'custom' },
            ]"
            class="w-40"
          />
        </Form.Item>
        <Form.Item v-if="filters.stockPoolType === 'market'" label="市场">
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
        <Form.Item v-else-if="filters.stockPoolType === 'watchlist'" label="股票池">
          <Select
            v-model:value="filters.stockPoolCode"
            :options="
              watchlists.filter((pool) => pool.poolId !== 'all').map((pool) => ({
                label: `${pool.poolName} (${pool.total})`,
                value: pool.poolId,
              }))
            "
            class="w-52"
            placeholder="请选择股票池"
          />
        </Form.Item>
        <Form.Item v-else label="股票代码">
          <Input
            v-model:value="filters.symbolsText"
            class="w-72"
            placeholder="如 000001.SZ,600519.SH"
          />
        </Form.Item>
        <Form.Item label="回测区间">
          <DatePicker.RangePicker
            v-model:value="filters.range"
            value-format="YYYY-MM-DD"
          />
        </Form.Item>
        <Form.Item label="持有天数">
          <Select
            v-model:value="filters.holdingPeriod"
            :options="[
              { label: '1 个交易日', value: 1 },
              { label: '3 个交易日', value: 3 },
              { label: '5 个交易日', value: 5 },
              { label: '10 个交易日', value: 10 },
            ]"
            class="w-36"
          />
        </Form.Item>
        <Form.Item>
          <Button :loading="loading" type="primary" @click="startBacktest">
            开始回测
          </Button>
        </Form.Item>
      </Form>
      <Typography.Paragraph class="mb-0 mt-2 text-xs" type="secondary">
        回测会把规则应用到所选日期范围的历史因子，并计算信号之后的实际涨跌；缺少因子的日期由后端自动补齐。
      </Typography.Paragraph>
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

    <Card class="mb-4" size="small" title="本次回测状态">
      <Space wrap>
        <Tag :color="statusColor(reportStatus)">
          状态：{{ statusText(reportStatus) }}
        </Tag>
        <Tag>样本数：{{ countDisplay(reportSampleCount) }}</Tag>
        <Tag color="blue">
          可评估：{{ countDisplay(reportEvaluatedCount) }}
        </Tag>
        <Tag :color="reportUnevaluableCount ? 'orange' : 'green'">
          不可评估：{{ countDisplay(reportUnevaluableCount) }}
        </Tag>
        <Tag v-if="report?.reportId" color="default">
          报告：{{ report.reportId }}
        </Tag>
      </Space>
      <Typography.Paragraph class="mb-0 mt-2 text-xs" type="secondary">
        样本数表示规则在历史因子上产生的信号数量；不可评估样本缺少持有期实际行情，未参与收益统计。
      </Typography.Paragraph>
    </Card>

    <Row :gutter="[16, 16]" class="mb-4">
      <Col :lg="15" :xs="24">
        <Card size="small" title="按信号日累计收益示意">
          <div v-if="equityBars.length > 0" class="flex h-64 items-end gap-2">
            <div
              v-for="point in equityBars"
              :key="`${point.date}-${point.value}`"
              class="flex flex-1 flex-col items-center gap-2"
            >
              <div
                class="w-full rounded bg-blue-500"
                :style="{ height: point.height }"
              ></div>
              <span class="text-center text-xs text-gray-500">
                {{ point.date.slice(2, 7) }}<br />{{ point.returnLabel }}
              </span>
            </div>
          </div>
          <Alert
            v-else
            message="本次回测暂无可评估样本的累计收益序列"
            show-icon
            type="info"
          />
          <Typography.Paragraph
            v-if="equityBars.length > 0"
            class="mb-0 mt-2 text-xs"
            type="secondary"
          >
            序列以 1.00 为起点，按信号日聚合每笔持有期净收益后累计；它不是逐日持仓净值，也不能代表未来收益。
          </Typography.Paragraph>
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
