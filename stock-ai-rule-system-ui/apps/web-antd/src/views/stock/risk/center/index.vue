<script lang="ts" setup>
import type {
  RiskHorizon,
  RiskObjectDetail,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskOverview,
  RiskTrendPoint,
} from '#/api/stock/risk/types';

import { computed, onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Descriptions,
  Empty,
  Input,
  message,
  Pagination,
  Select,
  Spin,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  getRiskObjectDetail,
  getRiskObjects,
  getRiskObjectTrend,
  getRiskOverview,
  RISK_DECISION_SUPPORT_NOTICE,
} from '#/api/stock/risk';

import RiskAlert from '../../components/risk-alert.vue';
import {
  RiskDimensionBars,
  RiskEvidenceList,
  RiskLevelTag,
  RiskScoreDisplay,
} from '../shared';
import {
  buildRiskHierarchy,
  buildRiskTriggerTimeline,
  buildSectorMatrix,
  createRiskCenterQuery,
  riskDataState,
} from './risk-center-state';
import RiskObjectDrilldown from './risk-object-drilldown.vue';
import RiskOverviewPanel from './risk-overview-panel.vue';
import RiskSectorMatrix from './risk-sector-matrix.vue';
import RiskTriggerTimeline from './risk-trigger-timeline.vue';

const loading = ref(false);
const detailLoading = ref(false);
const overview = ref<RiskOverview>();
const objectRows = ref<RiskObjectListItem[]>([]);
const objectTotal = ref(0);
const selected = ref<RiskObjectListItem>();
const detail = ref<RiskObjectDetail>();
const trend = ref<RiskTrendPoint[]>([]);
const query = reactive<RiskObjectQuery>(createRiskCenterQuery());

const horizonOptions: Array<{ label: string; value: RiskHorizon }> = [
  { label: '短期（1–5 日）', value: '1-5d' },
  { label: '中期（5–20 日）', value: '5-20d' },
  { label: '长期（20–60 日）', value: '20-60d' },
];

const stageLabels = {
  easing: '缓和',
  fragile: '脆弱',
  repricing: '重定价',
  stampede: '踩踏',
} as const;

const trendColumns = [
  { dataIndex: 'tradeDate', key: 'tradeDate', title: '交易日' },
  { dataIndex: 'totalScore', key: 'totalScore', title: '总分' },
  { dataIndex: 'level', key: 'level', title: '等级' },
  { dataIndex: 'completeness', key: 'completeness', title: '完整度' },
];

const sectorRows = computed(() => buildSectorMatrix(objectRows.value));
const hierarchy = computed(() =>
  buildRiskHierarchy(objectRows.value, selected.value),
);
const activeTimeline = computed(() =>
  buildRiskTriggerTimeline(detail.value ?? null),
);
const selectedDataState = computed(() =>
  riskDataState(detail.value?.snapshot ?? selected.value?.snapshot ?? null),
);

async function loadDetail(item: RiskObjectListItem) {
  selected.value = item;
  detailLoading.value = true;
  try {
    const params = { horizon: query.horizon, tradeDate: query.tradeDate };
    const [detailResult, trendResult] = await Promise.all([
      getRiskObjectDetail(item.object.objectType, item.object.objectId, params),
      getRiskObjectTrend(item.object.objectType, item.object.objectId, {
        horizon: query.horizon,
      }),
    ]);
    detail.value = detailResult;
    trend.value = trendResult;
  } catch (error) {
    detail.value = undefined;
    trend.value = [];
    message.error(error instanceof Error ? error.message : '风险详情加载失败');
  } finally {
    detailLoading.value = false;
  }
}

async function loadCenter() {
  loading.value = true;
  try {
    const [overviewResult, objectResult] = await Promise.all([
      getRiskOverview({
        horizon: query.horizon,
        tradeDate: query.tradeDate,
      }),
      getRiskObjects({ ...query }),
    ]);
    overview.value = overviewResult;
    objectRows.value = objectResult.rows;
    objectTotal.value = objectResult.total;

    const preferred = selected.value
      ? objectResult.rows.find(
          (item) => item.object.objectId === selected.value?.object.objectId,
        )
      : undefined;
    const initial =
      preferred ??
      objectResult.rows.find((item) => item.object.objectType === 'market') ??
      objectResult.rows[0];
    if (initial) {
      await loadDetail(initial);
    } else {
      selected.value = undefined;
      detail.value = undefined;
      trend.value = [];
    }
  } catch (error) {
    overview.value = undefined;
    objectRows.value = [];
    objectTotal.value = 0;
    message.error(error instanceof Error ? error.message : '风险中心加载失败');
  } finally {
    loading.value = false;
  }
}

async function changePage(pageNum: number) {
  query.pageNum = pageNum;
  await loadCenter();
}

async function changeHorizon(value: unknown) {
  if (typeof value !== 'string') {
    return;
  }
  query.horizon = value as RiskHorizon;
  query.pageNum = 1;
  await loadCenter();
}

async function search() {
  query.pageNum = 1;
  await loadCenter();
}

onMounted(loadCenter);
</script>

<template>
  <Page
    description="以三个风险周期展示市场、行业与个股的风险证据，仅作为辅助决策参考。"
    title="A 股风险中心"
  >
    <div class="risk-center">
      <RiskAlert :message="RISK_DECISION_SUPPORT_NOTICE" />

      <div class="toolbar">
        <Select
          :options="horizonOptions"
          :value="query.horizon"
          class="horizon-select"
          @change="changeHorizon"
        />
        <Input
          v-model:value="query.tradeDate"
          allow-clear
          class="trade-date"
          placeholder="交易日 YYYY-MM-DD"
          @press-enter="search"
        />
        <Input.Search
          v-model:value="query.keyword"
          allow-clear
          class="keyword-search"
          placeholder="对象名称或代码"
          @search="search"
        />
        <Button :loading="loading" @click="loadCenter">刷新</Button>
        <span class="coverage-note">正式风险态要求整体覆盖率 ≥ 80%</span>
      </div>

      <RiskOverviewPanel
        :loading="loading"
        :overview="overview"
        @select="loadDetail"
      />

      <RiskSectorMatrix
        :loading="loading"
        :rows="sectorRows"
        @select="loadDetail"
      />

      <RiskObjectDrilldown
        :hierarchy="hierarchy"
        :loading="loading"
        :selected-id="selected?.object.objectId"
        @select="loadDetail"
      />

      <Pagination
        v-if="objectTotal > (query.pageSize ?? 100)"
        :current="query.pageNum"
        :page-size="query.pageSize"
        :show-size-changer="false"
        :total="objectTotal"
        @change="changePage"
      />

      <Spin :spinning="detailLoading">
        <div v-if="detail" class="detail-grid">
          <Card size="small" title="对象风险详情">
            <template #extra>
              <RiskLevelTag :level="detail.snapshot.level" />
            </template>

            <Alert
              v-if="selectedDataState === 'insufficient'"
              class="detail-alert"
              message="数据完整度不足，当前不形成正式风险等级与闸门建议。"
              show-icon
              type="info"
            />
            <Alert
              v-else-if="selectedDataState === 'stale'"
              class="detail-alert"
              message="证据包含过期或不可用数据，请勿将当前结果视为正式风险态。"
              show-icon
              type="warning"
            />

            <Descriptions bordered :column="2" size="small">
              <Descriptions.Item label="对象">
                {{ detail.name }}（{{ detail.object.objectId }}）
              </Descriptions.Item>
              <Descriptions.Item label="周期">
                {{ detail.snapshot.horizon }}
              </Descriptions.Item>
              <Descriptions.Item label="交易日">
                {{ detail.snapshot.tradeDate }}
              </Descriptions.Item>
              <Descriptions.Item label="阶段">
                {{
                  detail.snapshot.stage
                    ? stageLabels[detail.snapshot.stage]
                    : '数据不足'
                }}
              </Descriptions.Item>
              <Descriptions.Item label="总分">
                <RiskScoreDisplay
                  :completeness="detail.snapshot.completeness"
                  :score="detail.snapshot.totalScore"
                />
              </Descriptions.Item>
              <Descriptions.Item label="风险置信度">
                {{
                  detail.snapshot.riskConfidence === null
                    ? '数据不足'
                    : `${Math.round(detail.snapshot.riskConfidence * 100)}%`
                }}
              </Descriptions.Item>
              <Descriptions.Item label="完整度">
                {{ Math.round(detail.snapshot.completeness * 100) }}%
              </Descriptions.Item>
              <Descriptions.Item label="模型版本">
                {{ detail.snapshot.modelVersion }}
              </Descriptions.Item>
            </Descriptions>

            <h4 class="section-title">V / T / S / C / A 维度</h4>
            <RiskDimensionBars :snapshot="detail.snapshot" />
          </Card>

          <RiskTriggerTimeline :items="activeTimeline" />

          <Card class="evidence-card" size="small" title="风险证据">
            <RiskEvidenceList :evidence="detail.snapshot.evidence" />
          </Card>

          <Card class="trend-card" size="small" title="风险趋势">
            <Table
              :columns="trendColumns"
              :data-source="trend"
              :pagination="false"
              row-key="tradeDate"
              size="small"
            >
              <template #bodyCell="{ column, record }">
                <RiskScoreDisplay
                  v-if="column.key === 'totalScore'"
                  :completeness="record.completeness"
                  :score="record.totalScore"
                />
                <RiskLevelTag
                  v-else-if="column.key === 'level'"
                  :level="record.level"
                />
                <span v-else-if="column.key === 'completeness'">
                  {{ Math.round(record.completeness * 100) }}%
                </span>
                <span v-else-if="column.key === 'tradeDate'">
                  {{ record.tradeDate }}
                </span>
              </template>
            </Table>
          </Card>
        </div>
        <Empty v-else description="请选择风险对象查看详情" />
      </Spin>

      <div class="shadow-note">
        <Tag color="blue">影子模式</Tag>
        风险闸门仅输出建议动作，不修改正式信号，也不会自动启用。
      </div>
    </div>
  </Page>
</template>

<style scoped>
.risk-center {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.horizon-select {
  width: 170px;
}

.trade-date {
  width: 180px;
}

.keyword-search {
  width: 240px;
}

.coverage-note {
  margin-left: auto;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  align-items: start;
}

.detail-alert {
  margin-bottom: 12px;
}

.section-title {
  margin: 16px 0 10px;
  font-weight: 600;
}

.evidence-card,
.trend-card {
  min-width: 0;
}

.shadow-note {
  padding: 10px 12px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--accent) / 30%);
  border-radius: 6px;
}

@media (max-width: 960px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .coverage-note {
    width: 100%;
    margin-left: 0;
  }
}
</style>
