<script lang="ts" setup>
import type {
  RiskHorizon,
  RiskObjectDetail,
  RiskObjectListItem,
  RiskObjectQuery,
  RiskOverview,
  RiskSyncJob,
  RiskTrendPoint,
} from '#/api/stock/risk/types';

import { computed, onMounted, onUnmounted, reactive, ref } from 'vue';

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
  getRiskSyncJob,
  getRiskSyncStatus,
  RISK_DECISION_SUPPORT_NOTICE,
  startRiskMarketSync,
  startRiskStockSync,
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
  buildRiskObjectQueries,
  buildRiskTrendQuery,
  buildRiskTriggerTimeline,
  buildSectorMatrix,
  createRequestSequence,
  createRiskCenterQuery,
  displayRiskAssessment,
  riskDataState,
} from './risk-center-state';
import RiskObjectDrilldown from './risk-object-drilldown.vue';
import RiskOverviewPanel from './risk-overview-panel.vue';
import RiskSectorMatrix from './risk-sector-matrix.vue';
import RiskSyncStatus from './risk-sync-status.vue';
import RiskTriggerTimeline from './risk-trigger-timeline.vue';

const loading = ref(false);
const detailLoading = ref(false);
const stockLoading = ref(false);
const overview = ref<RiskOverview>();
const marketRows = ref<RiskObjectListItem[]>([]);
const sectorObjectRows = ref<RiskObjectListItem[]>([]);
const stockRows = ref<RiskObjectListItem[]>([]);
const objectTotal = ref(0);
const activeSectorId = ref<string>();
const selected = ref<RiskObjectListItem>();
const detail = ref<RiskObjectDetail>();
const trend = ref<RiskTrendPoint[]>([]);
const syncJob = ref<RiskSyncJob>();
const query = reactive<RiskObjectQuery>(createRiskCenterQuery());
const centerRequests = createRequestSequence();
const detailRequests = createRequestSequence();
const stockRequests = createRequestSequence();
let syncPollTimer: ReturnType<typeof setTimeout> | undefined;

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

const sectorRows = computed(() => buildSectorMatrix(sectorObjectRows.value));
const hierarchy = computed(() =>
  buildRiskHierarchy(marketRows.value, sectorObjectRows.value, stockRows.value),
);
const activeTimeline = computed(() =>
  buildRiskTriggerTimeline(detail.value ?? null),
);
const selectedDataState = computed(() =>
  riskDataState(detail.value?.snapshot ?? selected.value?.snapshot ?? null),
);
const selectedAssessment = computed(() =>
  displayRiskAssessment(
    detail.value?.snapshot ?? selected.value?.snapshot ?? null,
  ),
);
const syncActive = computed(
  () =>
    syncJob.value?.status === 'queued' || syncJob.value?.status === 'running',
);

function resetSelection() {
  selected.value = undefined;
  detail.value = undefined;
  trend.value = [];
  detailLoading.value = false;
}

async function loadDetail(item: RiskObjectListItem) {
  const requestId = detailRequests.next();
  selected.value = item;
  detailLoading.value = true;
  try {
    const params = { horizon: query.horizon, tradeDate: query.tradeDate };
    const [detailResult, trendResult] = await Promise.all([
      getRiskObjectDetail(item.object.objectType, item.object.objectId, params),
      getRiskObjectTrend(
        item.object.objectType,
        item.object.objectId,
        buildRiskTrendQuery(query),
      ),
    ]);
    if (!detailRequests.isCurrent(requestId)) return;
    detail.value = detailResult;
    trend.value = trendResult;
  } catch (error) {
    if (!detailRequests.isCurrent(requestId)) return;
    selected.value = undefined;
    detail.value = undefined;
    trend.value = [];
    message.error(error instanceof Error ? error.message : '风险详情加载失败');
  } finally {
    if (detailRequests.isCurrent(requestId)) {
      detailLoading.value = false;
    }
  }
}

async function loadStockRows(parentSectorId = activeSectorId.value) {
  const requestId = stockRequests.next();
  stockLoading.value = true;
  try {
    const queries = buildRiskObjectQueries(query, parentSectorId);
    const result = await getRiskObjects(queries.stock);
    if (!stockRequests.isCurrent(requestId)) return;
    stockRows.value = result.rows;
    objectTotal.value = result.total;
  } catch (error) {
    if (!stockRequests.isCurrent(requestId)) return;
    stockRows.value = [];
    objectTotal.value = 0;
    message.error(
      error instanceof Error ? error.message : '个股风险列表加载失败',
    );
  } finally {
    if (stockRequests.isCurrent(requestId)) {
      stockLoading.value = false;
    }
  }
}

async function selectRiskObject(item: RiskObjectListItem) {
  if (item.object.objectType === 'sector') {
    activeSectorId.value = item.object.objectId;
    query.pageNum = 1;
    await Promise.all([loadStockRows(item.object.objectId), loadDetail(item)]);
    return;
  }
  if (item.object.objectType === 'market') {
    const shouldReloadStocks = activeSectorId.value !== undefined;
    activeSectorId.value = undefined;
    query.pageNum = 1;
    await Promise.all([
      shouldReloadStocks ? loadStockRows() : Promise.resolve(),
      loadDetail(item),
    ]);
    return;
  }
  await loadDetail(item);
}

async function loadCenter() {
  const requestId = centerRequests.next();
  detailRequests.next();
  stockRequests.next();
  stockLoading.value = false;
  loading.value = true;
  try {
    const queries = buildRiskObjectQueries(query, activeSectorId.value);
    const [overviewResult, marketResult, sectorResult, stockResult] =
      await Promise.allSettled([
        getRiskOverview({
          horizon: query.horizon,
          tradeDate: query.tradeDate,
        }),
        getRiskObjects(queries.market),
        getRiskObjects(queries.sector),
        getRiskObjects(queries.stock),
      ]);
    if (!centerRequests.isCurrent(requestId)) return;
    overview.value =
      overviewResult.status === 'fulfilled' ? overviewResult.value : undefined;
    marketRows.value =
      marketResult.status === 'fulfilled' ? marketResult.value.rows : [];
    sectorObjectRows.value =
      sectorResult.status === 'fulfilled' ? sectorResult.value.rows : [];
    stockRows.value =
      stockResult.status === 'fulfilled' ? stockResult.value.rows : [];
    objectTotal.value =
      stockResult.status === 'fulfilled' ? stockResult.value.total : 0;

    const allRows = [
      ...marketRows.value,
      ...sectorObjectRows.value,
      ...stockRows.value,
    ];
    const failedSections = [
      overviewResult.status === 'rejected' ? '总览' : '',
      marketResult.status === 'rejected' ? '市场' : '',
      sectorResult.status === 'rejected' ? '行业' : '',
      stockResult.status === 'rejected' ? '个股' : '',
    ].filter(Boolean);
    if (failedSections.length > 0) {
      message.warning(
        `${failedSections.join('、')}风险数据加载超时或失败，其余可用数据已正常展示`,
      );
    }

    const preferred = selected.value
      ? allRows.find(
          (item) =>
            item.object.objectType === selected.value?.object.objectType &&
            item.object.objectId === selected.value.object.objectId,
        )
      : undefined;
    const initial =
      preferred ??
      marketRows.value[0] ??
      sectorObjectRows.value[0] ??
      stockRows.value[0];
    if (initial) {
      await loadDetail(initial);
    } else {
      resetSelection();
    }
  } catch (error) {
    if (!centerRequests.isCurrent(requestId)) return;
    overview.value = undefined;
    marketRows.value = [];
    sectorObjectRows.value = [];
    stockRows.value = [];
    objectTotal.value = 0;
    activeSectorId.value = undefined;
    message.error(error instanceof Error ? error.message : '风险中心加载失败');
    resetSelection();
  } finally {
    if (centerRequests.isCurrent(requestId)) {
      loading.value = false;
    }
  }
}

async function changePage(pageNum: number) {
  query.pageNum = pageNum;
  detailRequests.next();
  resetSelection();
  await loadStockRows();
  const initial = stockRows.value[0];
  if (initial) {
    await loadDetail(initial);
  }
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

function clearSyncPolling() {
  clearTimeout(syncPollTimer);
  syncPollTimer = undefined;
}

function scheduleSyncPolling(jobId: string) {
  clearSyncPolling();
  syncPollTimer = setTimeout(() => void pollSyncJob(jobId), 1500);
}

async function pollSyncJob(jobId: string) {
  try {
    const result = await getRiskSyncJob(jobId);
    syncJob.value = result;
    if (result.status === 'queued' || result.status === 'running') {
      scheduleSyncPolling(jobId);
      return;
    }
    if (result.status === 'failed') {
      message.error(result.message || '风险数据同步失败，已保留原有数据');
      return;
    }
    if (result.status === 'partial_success') {
      message.warning(result.message || '风险数据部分同步成功');
    } else {
      message.success('风险数据同步完成');
    }
    await loadCenter();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '同步状态查询失败');
  }
}

async function loadInitialSyncStatus() {
  try {
    const result = await getRiskSyncStatus();
    const active =
      result.activeJobs.find((job) => job.scopeKey === 'market:CN-A') ??
      result.activeJobs[0];
    syncJob.value = active ?? result.latestMarketJob ?? undefined;
    if (active) {
      scheduleSyncPolling(active.jobId);
    }
  } catch {
    // 同步状态不影响风险数据的正常浏览。
  }
}

async function beginSync(request: () => Promise<RiskSyncJob>) {
  if (syncActive.value) return;
  try {
    const result = await request();
    syncJob.value = result;
    scheduleSyncPolling(result.jobId);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '启动风险同步失败');
  }
}

async function syncLatestMarket() {
  await beginSync(startRiskMarketSync);
}

async function syncSelectedStock() {
  const object = detail.value?.object ?? selected.value?.object;
  if (!object || object.objectType !== 'stock') return;
  await beginSync(() => startRiskStockSync(object.objectId));
}

onMounted(() => {
  void Promise.all([loadCenter(), loadInitialSyncStatus()]);
});
onUnmounted(clearSyncPolling);
</script>

<template>
  <Page
    description="以三个风险周期展示市场、行业与个股的风险证据，仅作为辅助决策参考。"
    title="A 股风险中心"
  >
    <div class="risk-center">
      <RiskAlert :message="RISK_DECISION_SUPPORT_NOTICE" />

      <RiskSyncStatus
        :job="syncJob"
        :latest-data-date="overview?.tradeDate"
        :stale-trading-days="overview?.marketSnapshot?.staleTradingDays"
        @sync-market="syncLatestMarket"
      />

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
        @select="selectRiskObject"
      />

      <RiskSectorMatrix
        :loading="loading"
        :rows="sectorRows"
        @select="selectRiskObject"
      />

      <RiskObjectDrilldown
        :hierarchy="hierarchy"
        :loading="loading || stockLoading"
        :selected-id="selected?.object.objectId"
        @select="selectRiskObject"
      />

      <Pagination
        v-if="objectTotal > (query.pageSize ?? 20)"
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
              <span class="detail-actions">
                <Button
                  v-if="detail.object.objectType === 'stock'"
                  :loading="syncActive"
                  size="small"
                  @click="syncSelectedStock"
                >
                  同步该股票
                </Button>
                <Tag v-if="selectedAssessment.provisional" color="gold">
                  暂定评估
                </Tag>
                <RiskLevelTag :level="selectedAssessment.level" />
              </span>
            </template>

            <Alert
              v-if="selectedDataState === 'unavailable'"
              class="detail-alert"
              message="风险数据源不可用，当前不形成正式风险等级与闸门建议。"
              show-icon
              type="error"
            />
            <Alert
              v-else-if="selectedDataState === 'stale'"
              class="detail-alert"
              message="证据包含过期数据，请勿将当前结果视为正式风险态。"
              show-icon
              type="warning"
            />
            <Alert
              v-else-if="selectedDataState === 'provisional'"
              class="detail-alert"
              message="当前为暂定评估：结论仅基于已可用指标，缺失指标可继续同步补齐；正式风险等级与闸门仍要求覆盖率达到 80%。"
              show-icon
              type="info"
            />
            <Alert
              v-else-if="selectedDataState === 'insufficient'"
              class="detail-alert"
              message="数据完整度不足，当前不形成正式风险等级与闸门建议。"
              show-icon
              type="info"
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
                    : selectedAssessment.provisional
                      ? '暂定评估'
                      : '数据不足'
                }}
              </Descriptions.Item>
              <Descriptions.Item label="总分">
                <RiskScoreDisplay
                  :completeness="detail.snapshot.completeness"
                  :provisional="selectedAssessment.provisional"
                  :score="selectedAssessment.score"
                />
              </Descriptions.Item>
              <Descriptions.Item label="风险置信度">
                {{
                  detail.snapshot.riskConfidence === null
                    ? selectedAssessment.provisional
                      ? '暂定结果不输出正式置信度'
                      : '数据不足'
                    : `${Math.round(detail.snapshot.riskConfidence * 100)}%`
                }}
              </Descriptions.Item>
              <Descriptions.Item label="完整度">
                {{ Math.round(detail.snapshot.completeness * 100) }}%
              </Descriptions.Item>
              <Descriptions.Item label="模型版本">
                {{ detail.snapshot.modelVersion }}
              </Descriptions.Item>
              <Descriptions.Item label="证据截至">
                {{ detail.snapshot.dataAsOf || '--' }}
              </Descriptions.Item>
              <Descriptions.Item label="数据时效">
                {{ detail.snapshot.staleTradingDays ?? 0 }} 个交易日
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

.detail-actions {
  display: inline-flex;
  gap: 6px;
  align-items: center;
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
