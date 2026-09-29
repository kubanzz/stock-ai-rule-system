<script lang="ts" setup>
import type {
  BacktestReportDetail,
  BacktestReportHistoryItem,
  BacktestReportOverview,
  BacktestReportResultPayload,
  BacktestRequest,
  WatchlistPool,
} from '#/api/stock';
import type { TablePaginationConfig } from 'ant-design-vue';

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
  message,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import {
  getBacktestReport,
  getBacktestReportHistory,
  getWatchlists,
  runBacktest,
  STOCK_RISK_DISCLAIMER,
} from '#/api/stock';

const loading = ref(false);
const historyLoading = ref(false);
const detailLoading = ref(false);
const report = ref<BacktestReportOverview>();
const reportMessage = ref('');
const selectedReport = ref<BacktestReportDetail>();
const selectedHistory = ref<BacktestReportHistoryItem>();
const historyRows = ref<BacktestReportHistoryItem[]>([]);
const historyTotal = ref(0);
const historyPage = ref(1);
const historyPageSize = ref(10);
const historyFilters = reactive({ objectType: 'all', objectCode: '' });
const watchlists = ref<WatchlistPool[]>([]);
let detailRequestId = 0;
let historyRequestId = 0;
let runRequestId = 0;

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

const reportNotice = computed(() => {
  if (reportMessage.value) return reportMessage.value;
  if (!report.value?.reportId) {
    return historyTotal.value === 0
      ? '暂无已保存的回测报告。设置参数后可开始第一次回测。'
      : '请选择一条历史记录查看报告。';
  }
  if (reportStatus.value === 'failed') return '本次回测失败，请检查报告状态。';
  if (reportStatus.value === 'skipped') {
    return '本次回测未产生可展示的结果，请检查规则和历史数据覆盖范围。';
  }
  return '';
});

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

function reportFromDetail(detail: BacktestReportDetail): BacktestReportOverview {
  return {
    comparison: [],
    cumulativeReturns: detail.cumulativeReturns,
    equityCurve: detail.equityCurve,
    failureSamples: detail.failureSamples,
    metrics: detail.metrics,
    reportId: detail.reportId,
    resultJson: detail.resultJson,
    riskDisclaimer: STOCK_RISK_DISCLAIMER,
    sampleCount: detail.sampleCount,
    evaluatedCount: detail.evaluatedCount,
    unevaluableCount: detail.unevaluableCount,
    status: detail.status,
  };
}

function formatScope(item: Pick<BacktestReportHistoryItem, 'stockPoolCode' | 'stockPoolType' | 'symbols'>) {
  if (item.stockPoolType === 'custom') return `自定义：${item.symbols?.join('、') || '未记录股票'}`;
  if (item.stockPoolType === 'market') return `市场：${item.stockPoolCode || '未记录'}`;
  if (item.stockPoolType === 'watchlist') return `股票池：${item.stockPoolCode || '未记录'}`;
  return '未记录';
}

function formatTime(value: null | string | undefined) {
  return value ? value.replace('T', ' ').slice(0, 16) : '--';
}

function objectTypeText(value: string) {
  return value === 'candidate_rule' ? '候选规则' : value === 'rule' ? '正式规则' : value;
}

async function selectHistoryReport(item: BacktestReportHistoryItem) {
  const requestId = ++detailRequestId;
  selectedHistory.value = item;
  selectedReport.value = undefined;
  report.value = undefined;
  reportMessage.value = '';
  detailLoading.value = true;
  try {
    const detail = await getBacktestReport(item.reportId);
    if (requestId !== detailRequestId) return;
    if (detail.reportId !== item.reportId || detail.objectCode !== item.objectCode
      || detail.objectType !== item.objectType) {
      throw new Error('报告详情与所选历史记录不匹配');
    }
    selectedReport.value = detail;
    report.value = reportFromDetail(detail);
  } catch (error) {
    if (requestId !== detailRequestId) return;
    reportMessage.value = error instanceof Error ? error.message : '报告详情加载失败';
    message.error(reportMessage.value);
  } finally {
    if (requestId === detailRequestId) detailLoading.value = false;
  }
}

async function loadHistory(selectFirst = true) {
  const requestId = ++historyRequestId;
  historyLoading.value = true;
  try {
    const result = await getBacktestReportHistory({
      objectCode: historyFilters.objectCode.trim() || undefined,
      objectType: historyFilters.objectType === 'all'
        ? undefined
        : historyFilters.objectType as BacktestRequest['objectType'],
      pageNum: historyPage.value,
      pageSize: historyPageSize.value,
    });
    if (requestId !== historyRequestId) return;
    historyRows.value = result.rows;
    historyTotal.value = result.total;
    if (selectFirst) {
      const first = result.rows[0];
      if (first) await selectHistoryReport(first);
      else {
        detailRequestId++;
        selectedHistory.value = undefined;
        selectedReport.value = undefined;
        report.value = undefined;
        reportMessage.value = '';
        detailLoading.value = false;
      }
    } else if (selectedHistory.value) {
      selectedHistory.value = result.rows.find((row) => row.reportId === selectedHistory.value?.reportId)
        ?? selectedHistory.value;
    }
  } catch (error) {
    if (requestId !== historyRequestId) return;
    message.error(error instanceof Error ? error.message : '历史回测加载失败');
  } finally {
    if (requestId === historyRequestId) historyLoading.value = false;
  }
}

async function searchHistory() {
  historyPage.value = 1;
  await loadHistory();
}

async function onHistoryPageChange(page: TablePaginationConfig) {
  historyPage.value = page.current ?? 1;
  historyPageSize.value = page.pageSize ?? historyPageSize.value;
  await loadHistory();
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
  const requestId = ++runRequestId;
  const selectionId = detailRequestId;
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
    const runResult = await runBacktest(payload);
    backtestFinished = true;
    if (runResult.id == null) {
      if (requestId === runRequestId) {
        message.warning('回测请求已执行，但接口没有返回报告编号，无法确认本次结果。请稍后重新查询。');
      }
      return;
    }
    const detail = await getBacktestReport(String(runResult.id));
    if (detail.reportId !== String(runResult.id)
      || detail.objectCode !== payload.objectCode
      || detail.objectType !== payload.objectType) {
      throw new Error('返回的报告与本次回测不匹配，请稍后重新查询');
    }
    if (requestId !== runRequestId || selectionId !== detailRequestId) return;
    detailRequestId++;
    selectedReport.value = detail;
    selectedHistory.value = {
      createdTime: detail.createdTime,
      endDate: detail.endDate,
      holdingPeriod: detail.holdingPeriod,
      objectCode: detail.objectCode,
      objectType: detail.objectType,
      reportId: detail.reportId,
      startDate: detail.startDate,
      status: detail.status,
      stockPoolCode: detail.stockPoolCode,
      stockPoolType: detail.stockPoolType,
      symbols: detail.symbols,
    };
    reportMessage.value = '';
    report.value = reportFromDetail(detail);
    historyFilters.objectType = 'all';
    historyFilters.objectCode = '';
    historyPage.value = 1;
    const notice = reportNotice.value;
    if (detail.status === 'failed') {
      message.error(notice || '本次回测失败');
    } else if (notice || detail.status !== 'success') {
      message.warning(notice || '本次回测未完成，请查看报告状态');
    } else {
      message.success('回测已完成，已展示本次报告');
    }
  } catch (error) {
    if (requestId !== runRequestId || selectionId !== detailRequestId) return;
    const reason = error instanceof Error ? error.message : '未知错误';
    const notice = backtestFinished
      ? `回测已执行，但本次报告加载失败：${reason}`
      : `回测执行失败：${reason}`;
    message.error(notice);
  } finally {
    if (requestId === runRequestId) {
      loading.value = false;
      if (backtestFinished) await loadHistory(false);
    }
  }
}

async function loadPage() {
  try {
    const [pools, history] = await Promise.allSettled([
      getWatchlists({ market: filters.market }),
      loadHistory(),
    ]);
    if (pools.status === 'fulfilled') {
      watchlists.value = pools.value;
      if (
        filters.stockPoolType === 'watchlist' &&
        !watchlists.value.some((pool) => pool.poolId === filters.stockPoolCode)
      ) {
        filters.stockPoolCode = watchlists.value.find((pool) => pool.poolId !== 'all')?.poolId ?? '';
      }
    } else {
      message.error('股票池加载失败');
    }
    if (history.status === 'rejected') {
      message.error('历史回测加载失败');
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '回测页面加载失败');
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
    filters.stockPoolCode = watchlists.value.find((pool) => pool.poolId !== 'all')?.poolId ?? '';
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
        filters.stockPoolCode = watchlists.value.find((pool) => pool.poolId !== 'all')?.poolId ?? '';
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
      :message="STOCK_RISK_DISCLAIMER"
      class="mb-4"
      show-icon
      type="warning"
    />

    <Alert
      v-if="!loading && !detailLoading && !historyLoading && reportNotice"
      :message="reportNotice"
      class="mb-4"
      show-icon
      :type="reportStatus === 'failed' ? 'error' : 'warning'"
    />

    <Card class="mb-4" size="small" title="新建回测参数">
      <Form layout="inline">
        <Form.Item label="回测对象">
          <Input v-model:value="filters.objectCode" class="w-56" />
        </Form.Item>
        <Form.Item label="对象类型">
          <Select
            v-model:value="filters.objectType"
            :disabled="loading"
            :options="[
              { label: '正式规则', value: 'rule' },
              { label: '候选规则', value: 'candidate_rule' },
            ]"
            class="w-32"
          />
        </Form.Item>
        <Form.Item label="股票范围">
          <Select
            v-model:value="filters.stockPoolType"
            :disabled="loading"
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
            :disabled="loading"
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
            :disabled="loading"
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
            :disabled="loading"
            class="w-72"
            placeholder="如 000001.SZ,600519.SH"
          />
        </Form.Item>
        <Form.Item label="回测区间">
          <DatePicker.RangePicker
            v-model:value="filters.range"
            :disabled="loading"
            value-format="YYYY-MM-DD"
          />
        </Form.Item>
        <Form.Item label="持有天数">
          <Select
            v-model:value="filters.holdingPeriod"
            :disabled="loading"
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
          <Button
            :disabled="!filters.objectCode"
            :loading="loading"
            type="primary"
            @click="startBacktest"
          >
            {{ loading ? '正在回测…' : '开始回测' }}
          </Button>
        </Form.Item>
      </Form>
      <Typography.Paragraph class="mb-0 mt-2 text-xs" type="secondary">
        回测会把规则应用到所选日期范围的历史因子，并计算信号之后的实际涨跌。回测完成后会保存本次结果，可在下方查看历史报告。
      </Typography.Paragraph>
    </Card>

    <Card class="mb-4" size="small" title="已保存报告">
      <Space class="mb-3" wrap>
        <Select
          v-model:value="historyFilters.objectType"
          :options="[
            { label: '全部类型', value: 'all' },
            { label: '正式规则', value: 'rule' },
            { label: '候选规则', value: 'candidate_rule' },
          ]"
          class="w-32"
          @change="searchHistory"
        />
        <Input.Search
          v-model:value="historyFilters.objectCode"
          allow-clear
          class="w-64"
          placeholder="按规则编码筛选历史"
          @search="searchHistory"
        />
      </Space>
      <Table
        :columns="[
          { title: '报告编号', dataIndex: 'reportId', key: 'reportId' },
          { title: '回测对象', dataIndex: 'objectCode', key: 'objectCode' },
          { title: '回测区间', key: 'range' },
          { title: '持有天数', dataIndex: 'holdingPeriod', key: 'holdingPeriod' },
          { title: '股票范围', key: 'scope' },
          { title: '状态', dataIndex: 'status', key: 'status' },
          { title: '创建时间', dataIndex: 'createdTime', key: 'createdTime' },
          { title: '操作', key: 'action' },
        ]"
        :data-source="historyRows"
        :loading="historyLoading"
        :pagination="{
          current: historyPage,
          pageSize: historyPageSize,
          total: historyTotal,
          showSizeChanger: true,
          pageSizeOptions: ['10', '20', '50'],
          showTotal: (total: number) => `共 ${total} 条`,
        }"
        row-key="reportId"
        size="small"
        :scroll="{ x: 1050 }"
        @change="onHistoryPageChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'objectCode'">
            {{ objectTypeText(record.objectType) }}：{{ record.objectCode }}
          </template>
          <template v-else-if="column.key === 'range'">
            {{ record.startDate || '--' }} 至 {{ record.endDate || '--' }}
          </template>
          <template v-else-if="column.key === 'holdingPeriod'">
            {{ record.holdingPeriod ?? '--' }} 个交易日
          </template>
          <template v-else-if="column.key === 'scope'">
            {{ formatScope(record) }}
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="statusColor(record.status)">{{ statusText(record.status) }}</Tag>
          </template>
          <template v-else-if="column.key === 'createdTime'">
            {{ formatTime(record.createdTime) }}
          </template>
          <template v-else-if="column.key === 'action'">
            <Button
              size="small"
              type="link"
              @click="selectHistoryReport(record as BacktestReportHistoryItem)"
            >
              {{ selectedHistory?.reportId === record.reportId ? '查看中' : '查看报告' }}
            </Button>
          </template>
        </template>
      </Table>
    </Card>

    <Card class="mb-4" size="small" title="所选报告详情" :loading="detailLoading">
      <template v-if="selectedReport">
        <Space wrap>
          <Tag>报告：{{ selectedReport.reportId }}</Tag>
          <Tag>{{ objectTypeText(selectedReport.objectType) }}：{{ selectedReport.objectCode }}</Tag>
          <Tag>区间：{{ selectedReport.startDate || '--' }} 至 {{ selectedReport.endDate || '--' }}</Tag>
          <Tag>持有：{{ selectedReport.holdingPeriod ?? '--' }} 个交易日</Tag>
          <Tag>范围：{{ formatScope(selectedReport) }}</Tag>
          <Tag>创建：{{ formatTime(selectedReport.createdTime) }}</Tag>
        </Space>
      </template>
      <Typography.Text v-else type="secondary">
        从已保存报告中选择一条查看，或开始新的回测。
      </Typography.Text>
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

    <Card v-if="report" class="mb-4" size="small" title="所选报告状态">
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

    <Row v-if="report" :gutter="[16, 16]" class="mb-4">
      <Col :xs="24">
        <Card size="small" title="历史权益曲线">
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
    </Row>

    <Card v-if="report" size="small" title="失败样本">
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
