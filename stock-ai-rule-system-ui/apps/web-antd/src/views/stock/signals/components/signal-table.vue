<script lang="ts" setup>
import type { TableColumnsType, TablePaginationConfig } from 'ant-design-vue';

import type { DashboardQueryState } from '../dashboard-state';

import type {
  DashboardSignalFilter,
  SignalDashboardRow,
  SignalType,
} from '#/api/stock';

import { computed, ref, watch } from 'vue';

import { RotateCw, Search } from '@vben/icons';

import {
  Button,
  Input,
  InputNumber,
  Progress,
  Select,
  Table,
  Tag,
  Tooltip,
} from 'ant-design-vue';

import { RiskGateTag, RiskLevelTag, RiskScoreDisplay } from '../../risk/shared';
import {
  formatConfidence,
  getRiskSnapshotState,
  RISK_SNAPSHOT_STATE_LABELS,
  RISK_STAGE_LABELS,
} from '../risk-dashboard-state';
import SignalRiskDetail from './signal-risk-detail.vue';

const props = defineProps<{
  industries: string[];
  loading?: boolean;
  query: DashboardQueryState;
  rows: SignalDashboardRow[];
  total: number;
}>();

const emit = defineEmits<{
  detail: [row: SignalDashboardRow];
  filters: [patch: Partial<DashboardQueryState>];
  page: [pageNum: number, pageSize: number];
  reset: [];
}>();

const keyword = ref(props.query.symbol ?? '');
watch(
  () => props.query.symbol,
  (value) => (keyword.value = value ?? ''),
);

const columns: TableColumnsType<SignalDashboardRow> = [
  {
    dataIndex: 'symbol',
    fixed: 'left',
    key: 'symbol',
    title: '股票',
    width: 142,
  },
  { dataIndex: 'price', key: 'price', title: '价格 / 涨跌', width: 116 },
  { dataIndex: 'signal', key: 'signal', title: '系统信号', width: 96 },
  {
    dataIndex: 'bullishScore',
    key: 'bullishScore',
    title: '看涨分',
    width: 82,
  },
  {
    dataIndex: 'bearishScore',
    key: 'bearishScore',
    title: '看跌分',
    width: 82,
  },
  {
    dataIndex: 'riskSnapshot',
    key: 'riskSnapshot',
    title: '风险预警',
    width: 168,
  },
  { dataIndex: 'confidence', key: 'confidence', title: '置信度', width: 130 },
  {
    dataIndex: 'riskGateDecision',
    key: 'riskGateDecision',
    title: '影子闸门',
    width: 176,
  },
  {
    dataIndex: 'triggeredRuleCount',
    key: 'triggeredRuleCount',
    title: '规则数',
    width: 76,
  },
  {
    dataIndex: 'suggestedPeriod',
    key: 'suggestedPeriod',
    title: '建议周期',
    width: 96,
  },
  { dataIndex: 'updatedAt', key: 'updatedAt', title: '更新时间', width: 148 },
  { fixed: 'right', key: 'actions', title: '操作', width: 70 },
];

const pagination = computed<TablePaginationConfig>(() => ({
  current: props.query.pageNum,
  pageSize: props.query.pageSize,
  pageSizeOptions: ['10', '20', '50', '100'],
  showQuickJumper: true,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
  total: props.total,
}));

const signalOptions = [
  { label: '全部信号', value: undefined },
  { label: '看涨', value: 'bullish' },
  { label: '看跌', value: 'bearish' },
  { label: '观望', value: 'watch' },
  { label: '历史高风险', value: 'high_risk' },
  { label: '待生成信号', value: 'pending' },
];

const signalMeta: Record<SignalType, { color: string; label: string }> = {
  bearish: { color: 'red', label: '看跌' },
  bullish: { color: 'green', label: '看涨' },
  high_risk: { color: 'volcano', label: '历史高风险' },
  watch: { color: 'gold', label: '观望' },
};

function percent(value: null | number) {
  if (value === null) return 0;
  return Math.round(value <= 1 ? value * 100 : value);
}

function scoreText(value: null | number) {
  return value === null ? '--' : value;
}

function riskStageText(record: Record<string, unknown>) {
  const row = record as unknown as SignalDashboardRow;
  const stage = row.riskSnapshot?.stage;
  return stage ? RISK_STAGE_LABELS[stage] : '--';
}

function riskState(record: Record<string, unknown>) {
  const row = record as unknown as SignalDashboardRow;
  return getRiskSnapshotState(row.riskSnapshot);
}

function handleTableChange(page: TablePaginationConfig) {
  emit('page', page.current ?? 1, page.pageSize ?? props.query.pageSize);
}

function runSearch() {
  emit('filters', { symbol: keyword.value.trim() || undefined });
}

function updateSignal(value: unknown) {
  emit('filters', {
    signal:
      typeof value === 'string' ? (value as DashboardSignalFilter) : undefined,
  });
}

function updateIndustry(value: unknown) {
  emit('filters', { industry: typeof value === 'string' ? value : undefined });
}

function updateConfidenceMin(value: unknown) {
  emit('filters', {
    confidenceMin: typeof value === 'number' ? value : undefined,
  });
}

function updateConfidenceMax(value: unknown) {
  emit('filters', {
    confidenceMax: typeof value === 'number' ? value : undefined,
  });
}

function getSignalMeta(value: unknown) {
  const record = value as SignalDashboardRow;
  if (record.signalStatus === 'pending') {
    return { color: 'default', label: '待生成信号' };
  }
  return (
    signalMeta[record.signal as SignalType] ?? {
      color: 'default',
      label: String(record.signal ?? '待生成信号'),
    }
  );
}

function openRecord(record: Record<string, unknown>) {
  emit('detail', record as unknown as SignalDashboardRow);
}
</script>

<template>
  <section class="table-shell">
    <div class="table-filterbar">
      <Input
        v-model:value="keyword"
        allow-clear
        class="search-input"
        placeholder="搜索股票代码 / 名称"
        @press-enter="runSearch"
      >
        <template #suffix>
          <Search class="search-icon" @click="runSearch" />
        </template>
      </Input>
      <Select
        allow-clear
        class="filter-select"
        placeholder="信号类型"
        :options="signalOptions"
        :value="query.signal"
        @update:value="updateSignal"
      />
      <Select
        allow-clear
        class="filter-select"
        placeholder="全部行业"
        :options="industries.map((item) => ({ label: item, value: item }))"
        :value="query.industry"
        @update:value="updateIndustry"
      />
      <div class="confidence-range">
        <span>置信度</span>
        <InputNumber
          :max="1"
          :min="0"
          :precision="2"
          :step="0.05"
          :value="query.confidenceMin"
          placeholder="最低"
          @update:value="updateConfidenceMin"
        />
        <span>至</span>
        <InputNumber
          :max="1"
          :min="0"
          :precision="2"
          :step="0.05"
          :value="query.confidenceMax"
          placeholder="最高"
          @update:value="updateConfidenceMax"
        />
      </div>
      <Tooltip title="重置表格筛选">
        <Button aria-label="重置表格筛选" @click="emit('reset')">
          <RotateCw class="reset-icon" />
        </Button>
      </Tooltip>
    </div>

    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      row-key="symbol"
      :scroll="{ x: 1470 }"
      size="small"
      @change="handleTableChange"
    >
      <template #expandedRowRender="{ record }">
        <SignalRiskDetail :row="record" />
      </template>
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'symbol'">
          <button class="stock-link" type="button" @click="openRecord(record)">
            <span>{{ record.symbol }}</span>
            <small>{{ record.name ?? '--' }}</small>
          </button>
        </template>
        <template v-else-if="column.key === 'price'">
          <div>{{ record.price ?? '--' }}</div>
          <small v-if="record.quoteStatus === 'pending'" class="muted">
            行情待同步
          </small>
          <small
            v-if="record.changePct !== undefined"
            :class="record.changePct >= 0 ? 'positive' : 'negative'"
          >
            {{ record.changePct >= 0 ? '+' : '' }}{{ record.changePct }}%
          </small>
        </template>
        <template v-else-if="column.key === 'signal'">
          <Tag :color="getSignalMeta(record).color">
            {{ getSignalMeta(record).label }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'confidence'">
          <span v-if="record.confidence === null">--</span>
          <Progress v-else :percent="percent(record.confidence)" size="small" />
        </template>
        <template v-else-if="column.key === 'riskSnapshot'">
          <div v-if="riskState(record) === 'ready'" class="risk-summary-cell">
            <div>
              <RiskLevelTag :level="record.riskSnapshot?.level" />
              <RiskScoreDisplay
                :completeness="record.riskSnapshot?.completeness"
                :score="record.riskSnapshot?.totalScore"
              />
            </div>
            <small>
              {{ riskStageText(record) }} · 完整度
              {{ Math.round((record.riskSnapshot?.completeness ?? 0) * 100) }}%
            </small>
          </div>
          <div v-else class="risk-summary-cell">
            <Tag :color="riskState(record) === 'stale' ? 'orange' : 'default'">
              {{ RISK_SNAPSHOT_STATE_LABELS[riskState(record)] }}
            </Tag>
            <small v-if="record.riskSnapshot">
              完整度
              {{ Math.round(record.riskSnapshot.completeness * 100) }}%
            </small>
          </div>
        </template>
        <template v-else-if="column.key === 'bullishScore'">
          {{ scoreText(record.bullishScore) }}
        </template>
        <template v-else-if="column.key === 'bearishScore'">
          {{ scoreText(record.bearishScore) }}
        </template>
        <template v-else-if="column.key === 'riskGateDecision'">
          <div v-if="record.riskGateDecision" class="gate-summary-cell">
            <RiskGateTag :status="record.riskGateDecision.suggestedAction" />
            <small>
              {{ formatConfidence(record.riskGateDecision.originalConfidence) }}
              →
              {{
                formatConfidence(record.riskGateDecision.suggestedConfidence)
              }}
            </small>
            <small>enforced=false · 未执行</small>
            <small
              v-if="record.signal === 'bearish' || record.signal === 'watch'"
            >
              仅风险说明
            </small>
          </div>
          <span v-else class="muted">暂无建议</span>
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button type="link" @click="openRecord(record)">详情</Button>
        </template>
      </template>
    </Table>
  </section>
</template>

<style scoped>
.table-shell {
  min-width: 0;
  overflow: hidden;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.table-filterbar {
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.search-input {
  width: 230px;
}

.filter-select {
  width: 130px;
}

.search-icon,
.reset-icon {
  width: 15px;
  height: 15px;
  cursor: pointer;
}

.confidence-range {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.confidence-range :deep(.ant-input-number) {
  width: 82px;
}

.stock-link {
  display: grid;
  padding: 0;
  color: #1677ff;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.stock-link small {
  max-width: 118px;
  overflow: hidden;
  text-overflow: ellipsis;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.positive {
  color: #16a36a;
}

.negative {
  color: #e5484d;
}

.risk-summary-cell,
.gate-summary-cell {
  display: grid;
  gap: 3px;
}

.risk-summary-cell > div {
  display: flex;
  gap: 4px;
  align-items: center;
}

.risk-summary-cell small,
.gate-summary-cell small,
.muted {
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

@media (max-width: 900px) {
  .table-filterbar {
    flex-wrap: wrap;
  }

  .search-input {
    flex: 1 1 220px;
  }
}
</style>
