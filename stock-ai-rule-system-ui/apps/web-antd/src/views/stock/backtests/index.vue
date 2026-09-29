<script lang="ts" setup>
import type {
  BacktestReportDetail,
  BacktestReportHistoryItem,
  BacktestReportOverview,
  BacktestReportResultPayload,
  BacktestRequest,
  CandidateRule,
  RuleDefinition,
  WatchlistPool,
} from '#/api/stock';
import type { TablePaginationConfig } from 'ant-design-vue';

import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';

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
import dayjs from 'dayjs';

import {
  getBacktestReport,
  getBacktestReportHistory,
  getCandidateRules,
  getRules,
  getWatchlists,
  runBacktest,
  STOCK_RISK_DISCLAIMER,
} from '#/api/stock';

const route = useRoute();
const loading = ref(false);
const objectLoading = ref(false);
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
const objectOptions = ref<{ label: string; value: string }[]>([]);
let objectRequestId = 0;
let detailRequestId = 0;
let historyRequestId = 0;
let runRequestId = 0;

function queryString(value: unknown) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value) && typeof value[0] === 'string') return value[0];
  return '';
}

function routeObjectType(): BacktestRequest['objectType'] {
  return queryString(route.query.objectType) === 'candidate_rule'
    ? 'candidate_rule'
    : 'rule';
}

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

const hasCurrentStatistics = computed(
  () => [2, 3].includes(resultPayload.value.statisticsVersion ?? 0),
);

const hasRuleDirectionStatistics = computed(
  () => resultPayload.value.statisticsVersion === 3
    && resultPayload.value.evaluationBasis === 'rule_direction',
);

const legacyStatisticsNotice = computed(() =>
  report.value?.reportId && !hasCurrentStatistics.value
    ? '旧报告使用原统计口径，观望信号可能计入收益样本；这里的 0% 不能视为真实方向胜率。历史记录未保存完整方向分布，请重新回测获取新口径结果。'
    : '',
);

const reportDirectionalCount = computed(() =>
  hasCurrentStatistics.value ? resultPayload.value.directionalCount : undefined,
);

const reportWatchCount = computed(() =>
  hasCurrentStatistics.value ? resultPayload.value.watchCount : undefined,
);

const reportUndirectedCount = computed(() =>
  hasRuleDirectionStatistics.value ? resultPayload.value.undirectedCount : undefined,
);

const ruleDirectionNotice = computed(() => {
  if (!report.value?.reportId || !hasRuleDirectionStatistics.value) return '';
  const period = selectedReport.value?.holdingPeriod;
  const periodText = period == null ? '持有期' : `${period} 日`;
  return `本报告的${periodText}规则方向命中率只统计规则自身给出方向、且持有期行情完整的样本。最终综合信号仍可能是观望；规则触发次数不等于可评估样本数。`;
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
  return report.value?.metrics.find((metric) => metric.label === label)?.value ?? undefined;
}

const reportSampleCount = computed(() => {
  if (hasCurrentStatistics.value) {
    return report.value?.sampleCount ?? resultPayload.value.signalCount ?? metricValue('触发次数');
  }
  const recordedCount = resultPayload.value.signalCount ?? report.value?.sampleCount;
  return recordedCount && recordedCount > 0
    ? recordedCount
    : (metricValue('触发次数') ?? recordedCount);
});

const reportEvaluatedCount = computed(() => {
  if (hasCurrentStatistics.value) {
    return report.value?.evaluatedCount ?? resultPayload.value.evaluatedCount;
  }
  const recordedCount = resultPayload.value.evaluatedCount ?? report.value?.evaluatedCount;
  return recordedCount && recordedCount > 0
    ? recordedCount
    : (resultPayload.value.triggerCount ?? metricValue('触发次数') ?? recordedCount);
});

const reportUnevaluableCount = computed(() => {
  if (hasCurrentStatistics.value) {
    return report.value?.unevaluableCount ?? resultPayload.value.unevaluableCount;
  }
  return resultPayload.value.unevaluableCount
    ?? (report.value?.unevaluableCount ? report.value.unevaluableCount : undefined)
    ?? resultPayload.value.skippedCount;
});

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
  if (reportStatus.value === 'failed') {
    return resultPayload.value.errorSummary || '本次回测失败，请检查规则内容和历史数据。';
  }
  if (hasCurrentStatistics.value) {
    if (!hasRuleDirectionStatistics.value
      && (resultPayload.value.emptyReasonCode === 'watch_only'
        || (reportDirectionalCount.value === 0 && (reportWatchCount.value ?? 0) > 0))) {
      return '规则已命中，但本次信号全部为观望；没有可计算收益的方向信号，收益指标不适用。';
    }
    if (reportSampleCount.value === 0) {
      return '本次回测没有产生历史预测信号，请检查所选股票池、日期范围和规则条件。';
    }
    if (reportDirectionalCount.value === 0) {
      return hasRuleDirectionStatistics.value
        ? '本次回测没有产生规则方向样本，收益指标不适用。'
        : '本次回测没有产生方向信号，收益指标不适用。';
    }
    if (resultPayload.value.emptyReason
      && !(hasRuleDirectionStatistics.value && resultPayload.value.emptyReasonCode === 'watch_only')) {
      return resultPayload.value.emptyReason;
    }
    if (reportEvaluatedCount.value === 0) {
      return hasRuleDirectionStatistics.value
        ? '本次回测产生了规则方向样本，但缺少对应持有期的实际行情，暂时无法计算收益。'
        : '本次回测产生了方向信号，但缺少对应持有期的实际行情，暂时无法计算收益。';
    }
  }
  if (resultPayload.value.emptyReason) return resultPayload.value.emptyReason;
  if (reportStatus.value === 'skipped') {
    return '本次回测未产生可展示的结果，请检查规则和历史数据覆盖范围。';
  }
  return '';
});

const equityEmptyNotice = computed(() => {
  if (!hasCurrentStatistics.value) return '旧报告未保存可还原的逐笔收益曲线，建议重新回测。';
  if (reportSampleCount.value === 0) return '本次没有规则触发信号，暂无收益曲线。';
  if (reportDirectionalCount.value === 0) return hasRuleDirectionStatistics.value
    ? '本次没有规则方向样本，没有可计算的收益曲线。'
    : '本次只有观望信号，没有可计算的收益曲线。';
  if (reportEvaluatedCount.value === 0) return hasRuleDirectionStatistics.value
    ? '规则方向样本缺少持有期实际行情，暂无收益曲线。'
    : '方向信号缺少持有期实际行情，暂无收益曲线。';
  return '本次回测未保存可展示的收益曲线。';
});

const emptyWatchlistNotice = computed(() => {
  if (filters.stockPoolType !== 'watchlist') return '';
  const selectedPool = watchlists.value.find(
    (pool) => pool.poolId === filters.stockPoolCode,
  );
  return selectedPool?.total === 0
    ? `“${selectedPool.poolName}”还没有股票。请先添加股票，或改选其他股票范围。`
    : '';
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
  objectCode: '',
  objectType: routeObjectType(),
  range: [dayjs().subtract(1, 'year').format('YYYY-MM-DD'), dayjs().format('YYYY-MM-DD')] as [string, string],
  holdingPeriod: 1,
  stockPoolType: 'watchlist' as NonNullable<BacktestRequest['stockPoolType']>,
  stockPoolCode: 'my-follow',
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
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '--';
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

function ruleOption(rule: RuleDefinition) {
  return {
    label: `${rule.ruleName}（${rule.ruleCode}）`,
    value: rule.ruleCode,
  };
}

function candidateOption(candidate: CandidateRule) {
  return {
    label: `${candidate.candidateCode}（目标规则：${candidate.targetRuleCode}）`,
    value: candidate.candidateCode,
  };
}

async function fetchBacktestObjects(objectType: BacktestRequest['objectType']) {
  if (objectType === 'rule') {
    return (await getRules()).rows
      .filter(
        (rule) =>
          rule.status === 'active' &&
          rule.ruleFormat === 'drools' &&
          rule.enabled !== false &&
          rule.ruleContent?.trim(),
      )
      .map(ruleOption);
  }
  return (await getCandidateRules()).rows
    .filter((candidate) => candidate.proposedContent?.trim())
    .map(candidateOption);
}

async function loadBacktestObjects(preferredCode = '') {
  const requestId = ++objectRequestId;
  const objectType = filters.objectType;
  objectLoading.value = true;
  objectOptions.value = [];
  filters.objectCode = '';
  try {
    const options = await fetchBacktestObjects(objectType);
    if (requestId !== objectRequestId) return;
    objectOptions.value = options;
    if (preferredCode) {
      filters.objectCode = options.some((option) => option.value === preferredCode)
        ? preferredCode
        : '';
      if (!filters.objectCode) message.warning('指定的回测对象当前不可用，请重新选择');
    } else {
      filters.objectCode = options[0]?.value ?? '';
    }
  } finally {
    if (requestId === objectRequestId) objectLoading.value = false;
  }
}

async function refreshObjects(preferredCode = '') {
  const objectType = filters.objectType;
  await loadBacktestObjects(preferredCode);
  if (objectType !== filters.objectType) return;
}

async function onObjectTypeChange() {
  try {
    await refreshObjects();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '回测对象加载失败');
  }
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

function preferredWatchlistPoolCode(pools: WatchlistPool[]) {
  return (
    pools.find((pool) => pool.poolId === 'my-follow')?.poolId ??
    pools.find((pool) => pool.poolId !== 'all')?.poolId ??
    ''
  );
}

async function startBacktest() {
  if (!objectOptions.value.some((option) => option.value === filters.objectCode)) {
    message.warning('请先选择回测对象');
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
    if (selectedPool.total === 0) {
      message.warning(`“${selectedPool.poolName}”还没有股票，请先添加股票或改选其他范围`);
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
    const [pools, objects, history] = await Promise.allSettled([
      getWatchlists({ market: filters.market }),
      refreshObjects(queryString(route.query.objectCode)),
      loadHistory(),
    ]);
    if (pools.status === 'fulfilled') {
      watchlists.value = pools.value;
      if (
        filters.stockPoolType === 'watchlist' &&
        !watchlists.value.some((pool) => pool.poolId === filters.stockPoolCode)
      ) {
        filters.stockPoolCode = preferredWatchlistPoolCode(watchlists.value);
      }
    } else {
      message.error('股票池加载失败');
    }
    if (objects.status === 'rejected') {
      message.error('回测对象加载失败');
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
  () => [route.query.objectType, route.query.objectCode],
  async () => {
    const code = queryString(route.query.objectCode);
    if (!code) return;
    const type = routeObjectType();
    if (type !== filters.objectType || !objectOptions.value.some((option) => option.value === code)) {
      filters.objectType = type;
      try {
        await refreshObjects(code);
      } catch (error) {
        message.error(error instanceof Error ? error.message : '回测对象加载失败');
      }
      return;
    }
    filters.objectCode = code;
  },
);

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
    filters.stockPoolCode = preferredWatchlistPoolCode(watchlists.value);
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
        filters.stockPoolCode = preferredWatchlistPoolCode(watchlists.value);
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
        <Form.Item label="对象类型">
          <Select
            v-model:value="filters.objectType"
            :disabled="loading"
            :options="[
              { label: '正式规则', value: 'rule' },
              { label: '候选规则', value: 'candidate_rule' },
            ]"
            class="w-32"
            @change="onObjectTypeChange"
          />
        </Form.Item>
        <Form.Item label="回测对象">
          <Select
            v-model:value="filters.objectCode"
            :disabled="loading"
            :loading="objectLoading"
            :not-found-content="objectLoading ? '正在加载规则' : '当前类型暂无可回测对象'"
            :options="objectOptions"
            :placeholder="objectLoading ? '正在加载规则' : '请选择回测对象'"
            class="w-80"
            option-filter-prop="label"
            show-search
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
            :disabled="objectLoading || !filters.objectCode"
            :loading="loading"
            type="primary"
            @click="startBacktest"
          >
            {{ loading ? '正在补齐行情并回测…' : '开始回测' }}
          </Button>
        </Form.Item>
      </Form>
      <Typography.Paragraph class="mb-0 mt-2 text-xs" type="secondary">
        回测会把规则应用到所选日期范围的历史因子，并计算信号之后的实际涨跌。我的股票池或自定义股票池不超过 20 只、回测区间不超过 3 年时，历史行情不足会尝试从真实数据源补采，再补算因子；无法补齐时会显示原因。
      </Typography.Paragraph>
      <Alert
        v-if="emptyWatchlistNotice"
        :message="emptyWatchlistNotice"
        class="mt-3"
        show-icon
        type="warning"
      />
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

    <Alert
      v-if="!detailLoading && legacyStatisticsNotice"
      :message="legacyStatisticsNotice"
      class="mb-4"
      show-icon
      type="warning"
    />
    <Alert
      v-if="!detailLoading && ruleDirectionNotice"
      :message="ruleDirectionNotice"
      class="mb-4"
      show-icon
      type="info"
    />

    <Row :gutter="[16, 16]" class="mb-4">
      <Col
        v-for="metric in report?.metrics ?? []"
        :key="metric.label"
        :lg="8"
        :sm="12"
        :xs="24"
        :xxl="4"
      >
        <Card size="small">
          <Statistic
            :suffix="metric.value == null ? undefined : metric.unit"
            :title="metric.label"
            :value="metric.value ?? '—'"
          />
        </Card>
      </Col>
    </Row>

    <Typography.Paragraph
      v-if="report && hasCurrentStatistics"
      class="mb-4 text-xs"
      type="secondary"
    >
      <template v-if="hasRuleDirectionStatistics">
        收益类指标仅统计规则给出方向且持有期行情完整的样本；“—”表示没有可计算样本。最终综合信号可能仍是观望。
      </template>
      <template v-else>
        收益类指标仅统计持有期行情完整的最终方向信号；“—”表示没有可计算样本。
      </template>
      样本复合收益按样本依次复合，并非实际投资组合收益。
    </Typography.Paragraph>

    <Card v-if="report" class="mb-4" size="small" title="所选报告状态">
      <Space wrap>
        <Tag :color="statusColor(reportStatus)">
          状态：{{ statusText(reportStatus) }}
        </Tag>
        <Tag>样本数：{{ countDisplay(reportSampleCount) }}</Tag>
        <Tag v-if="hasCurrentStatistics" color="cyan">
          {{ hasRuleDirectionStatistics ? '规则方向样本' : '方向信号' }}：{{ countDisplay(reportDirectionalCount) }}
        </Tag>
        <Tag v-if="hasCurrentStatistics" color="default">
          {{ hasRuleDirectionStatistics ? '最终观望' : '观望' }}：{{ countDisplay(reportWatchCount) }}
        </Tag>
        <Tag v-if="hasRuleDirectionStatistics" color="default">
          无规则方向：{{ countDisplay(reportUndirectedCount) }}
        </Tag>
        <Tag color="blue">
          可评估：{{ countDisplay(reportEvaluatedCount) }}
        </Tag>
        <Tag :color="reportUnevaluableCount ? 'orange' : 'green'">
          {{ hasRuleDirectionStatistics ? '缺行情' : '不可评估' }}：{{ countDisplay(reportUnevaluableCount) }}
        </Tag>
        <Tag v-if="report?.reportId" color="default">
          报告：{{ report.reportId }}
        </Tag>
      </Space>
      <Typography.Paragraph class="mb-0 mt-2 text-xs" type="secondary">
        <template v-if="hasRuleDirectionStatistics">
          样本数表示规则在历史因子上的命中次数；规则方向样本由规则本身给出的多空方向决定，最终观望数与规则方向样本可能重叠。可评估数仅包含持有期行情完整的规则方向样本；缺行情数不含无规则方向的样本。
        </template>
        <template v-else-if="hasCurrentStatistics">
          样本数表示规则在历史因子上产生的信号数量；方向信号包含看涨、看跌及按下跌方向评估的高风险信号。可评估数仅包含持有期行情完整的方向信号；不可评估数不含观望信号。
        </template>
        <template v-else>
          旧报告的样本和可评估数量沿用原统计口径，无法准确拆分观望与方向信号。
        </template>
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
            :message="equityEmptyNotice"
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

    <Card v-if="report" size="small" title="逐笔亏损样本">
      <Alert
        v-if="report.failureSamples.length === 0"
        message="本报告未保存逐笔亏损样本。"
        show-icon
        type="info"
      />
      <Table
        v-else
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
