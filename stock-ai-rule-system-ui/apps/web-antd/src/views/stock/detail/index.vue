<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type {
  PredictionRecord,
  ResearchSignalStatus,
  SignalTraceCondition,
  SignalTraceDecision,
  SignalTraceRuleEvaluation,
  SignalTraceScores,
  SignalType,
  StockResearchDetail,
} from '#/api/stock';

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
  Select,
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
const analysisVersionNo = computed(() => {
  const raw = route.query.versionNo;
  if (typeof raw !== 'string' || !/^[1-9]\d*$/.test(raw)) return undefined;
  return Number(raw);
});
const trace = computed(() => {
  const candidate = detail.value?.trace;
  return candidate && typeof candidate === 'object' ? candidate : undefined;
});
const traceRules = computed(() =>
  (Array.isArray(trace.value?.ruleEvaluations)
    ? trace.value.ruleEvaluations
    : []
  ).filter((rule): rule is SignalTraceRuleEvaluation =>
    !!rule && typeof rule === 'object' && typeof rule.code === 'string',
  ),
);
const signalRules = computed(() =>
  traceRules.value.filter((rule) => rule.status === 'MATCHED'),
);
const untracedMatchedRules = computed(() => {
  if (!trace.value) return [];
  const tracedCodes = new Set(traceRules.value.map((rule) => rule.code));
  return (detail.value?.ruleChain ?? []).filter((rule) =>
    rule.ruleCode && !tracedCodes.has(rule.ruleCode),
  );
});
const matchedRuleCount = computed(() =>
  trace.value
    ? signalRules.value.length + untracedMatchedRules.value.length
    : detail.value?.ruleChain.length ?? 0,
);
const versionOptions = computed(() => {
  const versions = detail.value?.versions ?? [];
  if (versions.length === 0) return [];
  return [
    { label: `当前保存版本 v${versions[0]?.versionNo}`, value: 'current' },
    ...versions.map((version) => ({
      label: `v${version.versionNo}${version.availableAt ? ` · ${version.availableAt.replace('T', ' ').slice(0, 19)}` : ''}`,
      value: String(version.versionNo),
    })),
  ];
});
const factorEvidence = computed(() =>
  Object.entries(
    trace.value?.factorSnapshot &&
    typeof trace.value.factorSnapshot === 'object' &&
    !Array.isArray(trace.value.factorSnapshot)
      ? trace.value.factorSnapshot
      : {},
  ),
);
function validScores(value: unknown): value is SignalTraceScores {
  if (!value || typeof value !== 'object') return false;
  const scores = value as Record<string, unknown>;
  return ['bullish', 'bearish', 'risk'].every(
    (key) => typeof scores[key] === 'number' && Number.isFinite(scores[key]),
  );
}

function validDecision(value: unknown): value is SignalTraceDecision {
  if (!value || typeof value !== 'object') return false;
  const decision = value as Record<string, unknown>;
  const thresholds = decision.thresholds;
  if (!thresholds || typeof thresholds !== 'object') return false;
  const limits = thresholds as Record<string, unknown>;
  return validScores(decision.rawScores) &&
    validScores(decision.effectiveScores) &&
    ['strongBullish', 'bullish', 'bearish', 'highRisk'].every(
      (key) => typeof limits[key] === 'number' && Number.isFinite(limits[key]),
    ) &&
    typeof decision.conflict === 'boolean' &&
    typeof decision.riskOverride === 'boolean' &&
    typeof decision.reason === 'string' &&
    typeof decision.signal === 'string' &&
    decision.signal in signalMeta;
}

const decision = computed(() =>
  validDecision(trace.value?.decision) ? trace.value?.decision : undefined,
);
const sourceClamps = computed(() =>
  (Array.isArray(decision.value?.sourceClamps)
    ? decision.value.sourceClamps
    : []
  ).filter((source) =>
    !!source &&
    typeof source === 'object' &&
    typeof source.format === 'string' &&
    validScores(source.signedScores) &&
    validScores(source.nonNegativeScores),
  ),
);
const pageFactors = computed(() =>
  (detail.value?.factors ?? []).filter(
    (item) => item.factor !== '风险分' && item.factor !== '规则置信度',
  ),
);
const traceUnavailable = computed(() =>
  detail.value?.signalStatus === 'ready' && !trace.value,
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
      analysisVersionNo.value,
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

function signalLabel(signal?: SignalType, status?: ResearchSignalStatus) {
  if (status === 'pending') return '待生成信号';
  return signal ? signalMeta[signal]?.label ?? '观望' : '观望';
}

function signalColor(signal?: SignalType, status?: ResearchSignalStatus) {
  if (status === 'pending') return 'default';
  return signal ? signalMeta[signal]?.color ?? 'gold' : 'gold';
}

function displayValue(value: unknown): string {
  if (value === null || value === undefined || value === '') return '未记录';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return '无法显示';
  }
}

function displayScore(value: null | number | undefined, signed = false): string {
  if (value === null || value === undefined) return '未记录';
  return `${signed && value > 0 ? '+' : ''}${value}`;
}

function ruleTitle(rule: SignalTraceRuleEvaluation): string {
  return rule.name && rule.name !== rule.code
    ? `${rule.code} · ${rule.name}`
    : rule.code;
}

function conditionStatus(condition: SignalTraceCondition): string {
  if (condition.status === 'MISSING') return '因子缺失';
  if (condition.status === 'INVALID') return '因子不可用';
  return condition.status === 'MATCHED' ? '满足' : '未满足';
}

function validConditions(value: unknown): SignalTraceCondition[] {
  if (!Array.isArray(value)) return [];
  return value.filter((condition): condition is SignalTraceCondition =>
    !!condition && typeof condition === 'object' &&
    typeof condition.field === 'string' &&
    typeof condition.operator === 'string' &&
    typeof condition.status === 'string',
  );
}

function openHistoryDate(date: string) {
  if (analysisDate.value === date && !analysisVersionNo.value) return;
  const { versionNo: _versionNo, ...query } = route.query;
  router.push({
    name: 'StockDetail',
    params: { symbol: symbol.value },
    query: { ...query, date },
  });
}

function openLatestDate() {
  const { date: _date, versionNo: _versionNo, ...query } = route.query;
  router.push({ name: 'StockDetail', params: { symbol: symbol.value }, query });
}

function openVersion(value: unknown) {
  if (value !== 'current' && (typeof value !== 'string' || !/^[1-9]\d*$/.test(value))) return;
  const date = detail.value?.signalDate;
  if (!date) return;
  if (value === 'current' && analysisDate.value === date && !analysisVersionNo.value) return;
  if (value !== 'current' && analysisDate.value === date && String(analysisVersionNo.value) === value) return;
  const { versionNo: _versionNo, ...query } = route.query;
  router.push({
    name: 'StockDetail',
    params: { symbol: symbol.value },
    query: { ...query, date, ...(value === 'current' ? {} : { versionNo: value }) },
  });
}

function historyRow(record: PredictionRecord) {
  return { onClick: () => openHistoryDate(record.date) };
}

watch(() => [route.params.symbol, route.query.date, route.query.versionNo], loadDetail, {
  immediate: true,
});
watch(priceSeries, renderPriceChart, { deep: true });
openDefaultWatchlistStock();
</script>

<template>
  <Page
    :description="`${symbol} 个股行情、因子证据、规则评估与历史辅助决策信号`"
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
        <div v-if="analysisDate" class="mb-4 flex items-center gap-3">
          <Typography.Text>
            正在查看 {{ analysisDate }} 的研究记录{{ analysisVersionNo ? ` · v${analysisVersionNo}` : '' }}
          </Typography.Text>
          <Button type="link" @click="openLatestDate">返回最新</Button>
        </div>
        <div v-if="detail.versions?.length" class="version-toolbar mb-4">
          <Typography.Text type="secondary">当日信号版本</Typography.Text>
          <Select
            :options="versionOptions"
            :value="analysisVersionNo ? String(analysisVersionNo) : 'current'"
            class="version-select"
            @change="openVersion"
          />
          <Typography.Text type="secondary">
            当前展示 v{{ detail.currentVersionNo ?? '未记录' }}；选择旧版本可查看当时保存的解释。
          </Typography.Text>
        </div>
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
                :precision="detail.signalStatus === 'ready' && detail.confidence !== null ? 1 : undefined"
                :value="detail.signalStatus === 'ready' ? detail.confidence ?? '未记录' : '未生成'"
                :suffix="detail.signalStatus === 'ready' && detail.confidence !== null ? '%' : ''"
                title="信号强度"
              />
              <Typography.Text class="text-xs" type="secondary">
                规则分数映射值，非预测概率
              </Typography.Text>
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Statistic
                :precision="detail.signalStatus === 'ready' && detail.riskScore !== null ? 0 : undefined"
                :value="detail.signalStatus === 'ready' ? detail.riskScore ?? '未记录' : '未生成'"
                title="风险分"
              />
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Typography.Text type="secondary">当前信号</Typography.Text>
              <div class="mt-2">
                <Tag :color="signalColor(detail.signal, detail.signalStatus)">
                  {{ signalLabel(detail.signal, detail.signalStatus) }}
                </Tag>
              </div>
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <Statistic
                :value="matchedRuleCount"
                title="命中规则数"
              />
            </Card>
          </Col>
          <Col :lg="4" :sm="12" :xs="24">
            <Card size="small">
              <div class="date-summary">
                <div><span>信号日期</span><b>{{ detail.signalDate || '暂无信号' }}</b></div>
                <div>
                  <span>{{ trace ? '输入因子日期' : '页面因子日期' }}</span>
                  <b>{{ trace?.factorDate || detail.factorDate || '未记录' }}</b>
                </div>
                <div><span>行情日期</span><b>{{ detail.quoteDate || priceSeries.at(-1)?.date || '暂无行情' }}</b></div>
              </div>
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
            <Card size="small" title="辅助决策结论">
              <Alert
                v-if="detail.signalStatus === 'pending'"
                class="mb-3"
                message="该日尚无已生成的信号。"
                show-icon
                type="info"
              />
              <div v-else-if="decision" class="mb-3">
                <Tag :color="signalColor(decision.signal)">
                  {{ signalLabel(decision.signal) }}
                </Tag>
                <Tag v-if="decision.level">{{ decision.level }}</Tag>
                <Typography.Paragraph class="mb-0 mt-2">
                  {{ decision.reason }}
                </Typography.Paragraph>
              </div>
              <Typography.Paragraph
                v-if="detail.explanation && detail.explanation !== decision?.reason"
              >
                <Typography.Text v-if="decision" strong>规则说明汇总：</Typography.Text>
                {{ detail.explanation }}
              </Typography.Paragraph>
              <Alert
                v-if="detail.traceStatus === 'partial'"
                class="mb-3"
                :message="trace ? '本次推理轨迹仅部分完整，请按各规则证据状态解读。' : '已保存的推理轨迹无法解析，逐条证据暂不可用。'"
                show-icon
                type="warning"
              />
              <Alert
                v-else-if="traceUnavailable"
                class="mb-3"
                message="历史信号未记录逐条条件与分数贡献，无法还原当时完整的规则触发链。"
                show-icon
                type="info"
              />
              <Space wrap>
                <Tag v-for="rule in signalRules" :key="rule.code" color="geekblue">
                  {{ rule.code }}@{{ rule.version }}
                </Tag>
                <Tag v-for="item in untracedMatchedRules" :key="item.ruleCode" color="orange">
                  {{ item.ruleCode }} · 详细轨迹未记录
                </Tag>
                <Tag v-for="item in trace ? [] : detail.ruleChain" :key="item.ruleCode" color="geekblue">
                  {{ item.ruleCode }}
                </Tag>
              </Space>
            </Card>
          </Col>
        </Row>

        <Row :gutter="[16, 16]" class="mb-4">
          <Col :lg="12" :xs="24">
            <Card size="small" :title="trace ? '生成时因子证据' : '页面因子状态'">
              <Typography.Paragraph v-if="trace" type="secondary">
                输入快照日期：{{ trace.factorDate || '未记录' }}。以下为信号生成时保存的原始因子值。
              </Typography.Paragraph>
              <Alert
                v-else-if="traceUnavailable"
                class="mb-3"
                message="这些因子是当前页面查询结果，未证实为历史信号生成时的输入。"
                show-icon
                type="info"
              />
              <List v-if="trace && factorEvidence.length" :data-source="factorEvidence" size="small">
                <template #renderItem="{ item }">
                  <List.Item>
                    <Typography.Text strong>{{ item[0] }}</Typography.Text>
                    <Typography.Text class="factor-value">{{ displayValue(item[1]) }}</Typography.Text>
                  </List.Item>
                </template>
              </List>
              <Empty
                v-else-if="trace"
                :image="Empty.PRESENTED_IMAGE_SIMPLE"
                description="本次信号没有保存可展示的因子快照"
              />
              <List v-else-if="pageFactors.length" :data-source="pageFactors" size="small">
                <template #renderItem="{ item }">
                  <List.Item>
                    <List.Item.Meta
                      :description="item.description"
                      :title="item.factor"
                    />
                    <Typography.Text class="factor-value">
                      {{ displayValue(item.value) }}
                    </Typography.Text>
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
              <Empty
                v-else
                :image="Empty.PRESENTED_IMAGE_SIMPLE"
                :description="traceUnavailable ? '历史信号缺少当时的因子记录' : '当前交易日尚未生成因子数据'"
              >
                <template #footer>
                  <Typography.Paragraph v-if="!traceUnavailable" type="secondary" class="mb-2">
                    请运行每日工作流完成行情同步和因子计算后再查看。
                  </Typography.Paragraph>
                  <Button v-if="!traceUnavailable" type="primary" @click="router.push('/stock/workflow')">
                    去运行工作流
                  </Button>
                </template>
              </Empty>
            </Card>
          </Col>
          <Col :lg="12" :xs="24">
            <Card size="small" :title="trace ? '规则评估' : '命中规则'">
              <List v-if="traceRules.length" :data-source="traceRules" size="small">
                <template #renderItem="{ item }">
                  <List.Item class="rule-item">
                    <div class="rule-evaluation">
                      <div class="rule-heading">
                        <Typography.Text strong>{{ ruleTitle(item) }}</Typography.Text>
                        <Space wrap>
                          <Tag :color="item.status === 'MATCHED' ? 'green' : 'default'">
                            {{ item.status === 'MATCHED' ? '命中' : '未命中' }}
                          </Tag>
                          <Tag v-if="item.evidenceStatus !== 'FULL'" color="orange">
                            {{ item.evidenceStatus === 'PARTIAL' ? '证据部分记录' : '证据未记录' }}
                          </Tag>
                        </Space>
                      </div>
                      <Typography.Text type="secondary">
                        版本 {{ displayValue(item.version) }} · {{ item.format }} · 优先级 {{ item.priority }}
                      </Typography.Text>
                      <div v-if="validConditions(item.conditions).length" class="condition-list">
                        <div v-for="(condition, index) in validConditions(item.conditions)" :key="`${condition.field}-${index}`">
                          <Tag :color="condition.status === 'MATCHED' ? 'green' : condition.status === 'MISSING' || condition.status === 'INVALID' ? 'orange' : 'default'">
                            {{ conditionStatus(condition) }}
                          </Tag>
                          {{ condition.field }} {{ condition.operator }} {{ displayValue(condition.expected) }}
                          <Typography.Text type="secondary">
                            （实际：{{ displayValue(condition.actual) }}）
                          </Typography.Text>
                        </div>
                      </div>
                      <Typography.Text v-else type="secondary">逐条条件未记录</Typography.Text>
                      <div class="rule-deltas">
                        <span>看涨 {{ displayScore(item.bullishDelta, true) }}</span>
                        <span>看跌 {{ displayScore(item.bearishDelta, true) }}</span>
                        <span>风险 {{ displayScore(item.riskDelta, true) }}</span>
                      </div>
                      <Typography.Text v-if="item.explanation" type="secondary">
                        {{ item.status === 'MATCHED' ? '命中说明' : '规则预设说明（未触发）' }}：{{ item.explanation }}
                      </Typography.Text>
                    </div>
                  </List.Item>
                </template>
              </List>
              <Alert
                v-if="untracedMatchedRules.length"
                class="my-3"
                :message="`${untracedMatchedRules.length} 条已命中规则只有编码，未记录逐条条件或分数贡献。`"
                show-icon
                type="warning"
              />
              <List v-if="untracedMatchedRules.length" :data-source="untracedMatchedRules" size="small">
                <template #renderItem="{ item }">
                  <List.Item>
                    <List.Item.Meta
                      :description="item.condition || '详细轨迹未记录'"
                      :title="item.ruleName && item.ruleName !== item.ruleCode ? `${item.ruleCode} · ${item.ruleName}` : item.ruleCode"
                    />
                    <Typography.Text type="secondary">贡献：未记录</Typography.Text>
                  </List.Item>
                </template>
              </List>
              <Empty
                v-if="trace && !traceRules.length && !untracedMatchedRules.length"
                :image="Empty.PRESENTED_IMAGE_SIMPLE"
                description="本次没有可评估的规则"
              />
              <List v-if="!trace && detail.ruleChain.length" :data-source="detail.ruleChain" size="small">
                <template #renderItem="{ item }">
                  <List.Item>
                    <List.Item.Meta
                      :description="item.condition"
                      :title="item.ruleName && item.ruleName !== item.ruleCode ? `${item.ruleCode} · ${item.ruleName}` : item.ruleCode"
                    />
                    <Typography.Text type="secondary">
                      贡献：{{ displayScore(item.contribution, true) }}
                    </Typography.Text>
                  </List.Item>
                </template>
              </List>
              <Empty v-if="!trace && !detail.ruleChain.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无命中规则" />
            </Card>
          </Col>
        </Row>

        <Card v-if="decision" class="mb-4" size="small" title="分数与决策过程">
          <div class="score-flow mb-3">
            <div>
              <Typography.Text strong>逐条规则净贡献</Typography.Text>
              <Typography.Paragraph class="mb-0">
                看涨 {{ displayScore(decision.signedRuleTotals?.bullish, true) }} · 看跌 {{ displayScore(decision.signedRuleTotals?.bearish, true) }} · 风险 {{ displayScore(decision.signedRuleTotals?.risk, true) }}
              </Typography.Paragraph>
            </div>
            <div v-for="source in sourceClamps" :key="source.format">
              <Typography.Text strong>{{ source.format }} 来源归一化</Typography.Text>
              <Typography.Paragraph class="mb-0">
                净值 {{ displayScore(source.signedScores.bullish, true) }} / {{ displayScore(source.signedScores.bearish, true) }} / {{ displayScore(source.signedScores.risk, true) }}
                → 非负值 {{ displayScore(source.nonNegativeScores.bullish) }} / {{ displayScore(source.nonNegativeScores.bearish) }} / {{ displayScore(source.nonNegativeScores.risk) }}
              </Typography.Paragraph>
            </div>
          </div>
          <Row :gutter="[16, 16]">
            <Col :md="8" :xs="24">
              <div class="decision-step">
                <h4>1. 原始分数</h4>
                <p>看涨 {{ displayScore(decision.rawScores.bullish) }} · 看跌 {{ displayScore(decision.rawScores.bearish) }} · 风险 {{ displayScore(decision.rawScores.risk) }}</p>
              </div>
            </Col>
            <Col :md="8" :xs="24">
              <div class="decision-step">
                <h4>2. 有效分数</h4>
                <p>看涨 {{ displayScore(decision.effectiveScores.bullish) }} · 看跌 {{ displayScore(decision.effectiveScores.bearish) }} · 风险 {{ displayScore(decision.effectiveScores.risk) }}</p>
                <Typography.Text type="secondary">用于与规则阈值比较</Typography.Text>
              </div>
            </Col>
            <Col :md="8" :xs="24">
              <div class="decision-step">
                <h4>3. 冲突与风险</h4>
                <p>{{ decision.conflict ? '看涨与看跌阈值同时达到，方向转为观望。' : '未触发多空冲突。' }}</p>
                <p>{{ decision.riskOverride ? '风险分达到高风险阈值，最终信号为高风险。' : '未触发高风险覆盖。' }}</p>
              </div>
            </Col>
          </Row>
          <Typography.Text type="secondary">
            阈值：强看涨 ≥{{ decision.thresholds.strongBullish }}；看涨 ≥{{ decision.thresholds.bullish }}；看跌 ≥{{ decision.thresholds.bearish }}；高风险 ≥{{ decision.thresholds.highRisk }}。
          </Typography.Text>
          <div class="mt-3">
            <Typography.Text strong>最终信号：</Typography.Text>
            <Tag :color="signalColor(decision.signal)">{{ signalLabel(decision.signal) }}</Tag>
            <Typography.Text>{{ decision.reason }}</Typography.Text>
          </div>
        </Card>

        <Card size="small" title="历史预测记录">
          <Table
            :columns="[
              { title: '信号日期', dataIndex: 'date' },
              { title: '预测信号', dataIndex: 'signal' },
              { title: '预测方向', dataIndex: 'direction' },
              { title: '信号强度', dataIndex: 'confidence' },
              { title: '实际收益', dataIndex: 'actualReturn' },
              { title: '是否命中', dataIndex: 'hitStatus' },
            ]"
            :custom-row="historyRow"
            :data-source="detail.history"
            :pagination="false"
            :row-class-name="() => 'history-row'"
            row-key="date"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'date'">
                <Button type="link" @click.stop="openHistoryDate(record.date)">
                  {{ record.date }}
                </Button>
              </template>
              <template v-else-if="column.dataIndex === 'signal'">
                <Tag :color="signalColor(record.signal)">
                  {{ signalLabel(record.signal) }}
                </Tag>
              </template>
              <template v-else-if="column.dataIndex === 'confidence'">
                {{ record.confidence === null || record.confidence === undefined ? '未记录' : `${record.confidence}%` }}
              </template>
              <template v-else-if="column.dataIndex === 'actualReturn'">
                <span
                  v-if="record.actualReturn !== null && record.actualReturn !== undefined"
                  :class="
                    record.actualReturn >= 0
                      ? 'text-green-500'
                      : 'text-red-500'
                  "
                >
                  {{ record.actualReturn }}%
                </span>
                <span v-else>待验证</span>
              </template>
            </template>
          </Table>
        </Card>
      </template>
    </Skeleton>
  </Page>
</template>

<style scoped>
.date-summary {
  display: grid;
  gap: 2px;
}

.version-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; }
.version-select { min-width: 270px; }

.date-summary div {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
}

.date-summary span { color: #64748b; }
.date-summary b { color: #334155; font-weight: 600; }

.factor-value {
  max-width: 45%;
  overflow-wrap: anywhere;
  text-align: right;
}

.rule-item { align-items: stretch; }
.rule-evaluation { width: 100%; }
.rule-heading { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; }
.condition-list { display: grid; gap: 6px; margin: 8px 0; overflow-wrap: anywhere; }
.rule-deltas { display: flex; flex-wrap: wrap; gap: 12px; margin: 8px 0; font-weight: 600; }

.decision-step {
  height: 100%;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
}

.decision-step h4 { margin: 0 0 8px; font-weight: 600; }
.decision-step p { margin: 0 0 6px; }
.score-flow { display: flex; flex-wrap: wrap; gap: 8px 20px; }
:deep(.history-row) { cursor: pointer; }
:deep(.history-row:hover) { background: #f8fafc; }

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
