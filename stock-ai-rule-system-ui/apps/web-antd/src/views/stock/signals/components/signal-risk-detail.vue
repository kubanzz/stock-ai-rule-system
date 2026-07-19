<script lang="ts" setup>
import type { SignalDashboardRow } from '#/api/stock';

import { computed } from 'vue';

import { Alert, Tag } from 'ant-design-vue';

import {
  RiskDimensionBars,
  RiskEvidenceList,
  RiskGateTag,
  RiskLevelTag,
  RiskScoreDisplay,
} from '../../risk/shared';
import {
  formatConfidence,
  getRiskSnapshotState,
  isExplanationOnlyDirection,
  RISK_SNAPSHOT_STATE_LABELS,
  RISK_STAGE_LABELS,
} from '../risk-dashboard-state';

const props = defineProps<{ row: SignalDashboardRow }>();

const snapshotState = computed(() =>
  getRiskSnapshotState(props.row.riskSnapshot),
);
const explanationOnly = computed(() => {
  const direction = props.row.riskGateDecision?.signalDirection;
  return direction ? isExplanationOnlyDirection(direction) : false;
});

function displayStage() {
  const stage = props.row.riskSnapshot?.stage;
  return stage ? RISK_STAGE_LABELS[stage] : '--';
}
</script>

<template>
  <div class="risk-detail-grid">
    <section class="risk-detail-panel">
      <div class="panel-heading">
        <div>
          <strong>风险快照</strong>
          <small>
            {{ row.riskSnapshot?.horizon ?? '当前周期' }} ·
            {{ row.riskSnapshot?.tradeDate ?? '暂无交易日' }}
          </small>
        </div>
        <Tag v-if="snapshotState !== 'ready'" color="default">
          {{ RISK_SNAPSHOT_STATE_LABELS[snapshotState] }}
        </Tag>
      </div>

      <Alert
        v-if="snapshotState === 'empty'"
        description="当前对象在所选周期没有可用风险数据。"
        message="暂无风险快照"
        show-icon
        type="info"
      />
      <Alert
        v-else-if="snapshotState === 'insufficient'"
        description="完整度未达到 80%，风险等级和闸门不出正式值。"
        message="风险数据不足"
        show-icon
        type="warning"
      />
      <Alert
        v-else-if="snapshotState === 'stale'"
        description="过期证据只用于人工复核，不应据此执行闸门。"
        message="风险证据已过期"
        show-icon
        type="warning"
      />

      <template v-if="row.riskSnapshot">
        <div class="snapshot-summary">
          <div>
            <span>风险等级</span>
            <RiskLevelTag :level="row.riskSnapshot.level" />
          </div>
          <div>
            <span>风险强度分</span>
            <RiskScoreDisplay
              :completeness="row.riskSnapshot.completeness"
              :score="row.riskSnapshot.totalScore"
            />
          </div>
          <div>
            <span>阶段</span>
            <strong>{{ displayStage() }}</strong>
          </div>
          <div>
            <span>完整度</span>
            <strong>
              {{ Math.round(row.riskSnapshot.completeness * 100) }}%
            </strong>
          </div>
          <div>
            <span>风险置信度</span>
            <strong>
              {{ formatConfidence(row.riskSnapshot.riskConfidence) }}
            </strong>
          </div>
          <div>
            <span>M 门控系数</span>
            <strong>{{ row.riskSnapshot.mScore ?? '--' }}</strong>
          </div>
        </div>

        <div class="detail-section">
          <h4>V / T / S / C / A 维度</h4>
          <RiskDimensionBars :snapshot="row.riskSnapshot" />
        </div>
        <div class="detail-section">
          <h4>风险证据</h4>
          <RiskEvidenceList :evidence="row.riskSnapshot.evidence" />
        </div>
      </template>
    </section>

    <section class="risk-detail-panel gate-panel">
      <div class="panel-heading">
        <div>
          <strong>影子闸门</strong>
          <small>仅记录建议，不修改正式信号</small>
        </div>
        <Tag color="blue">影子模式 · 未执行</Tag>
      </div>

      <Alert
        v-if="!row.riskGateDecision"
        description="原始信号与置信度保持不变。"
        message="暂无影子闸门建议"
        show-icon
        type="info"
      />
      <template v-else>
        <div class="gate-summary">
          <div>
            <span>建议动作</span>
            <RiskGateTag :status="row.riskGateDecision.suggestedAction" />
          </div>
          <div>
            <span>原始置信度</span>
            <strong>
              {{ formatConfidence(row.riskGateDecision.originalConfidence) }}
            </strong>
          </div>
          <div>
            <span>建议置信度</span>
            <strong>
              {{ formatConfidence(row.riskGateDecision.suggestedConfidence) }}
            </strong>
          </div>
          <div>
            <span>执行状态</span>
            <strong>enforced=false（未执行）</strong>
          </div>
        </div>

        <Alert
          v-if="explanationOnly"
          description="看跌与观望只附加风险说明，不改写方向或置信度。"
          message="风险说明模式"
          show-icon
          type="info"
        />

        <div class="gate-reason">
          <span>建议原因</span>
          <p>{{ row.riskGateDecision.reason }}</p>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.risk-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.65fr);
  gap: 14px;
  padding: 8px 16px 16px 48px;
}

.risk-detail-panel {
  min-width: 0;
  padding: 16px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.panel-heading {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 14px;
}

.panel-heading > div {
  display: grid;
  gap: 3px;
}

.panel-heading small,
.snapshot-summary span,
.gate-summary span,
.gate-reason span {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.snapshot-summary,
.gate-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}

.snapshot-summary > div,
.gate-summary > div {
  display: grid;
  gap: 5px;
  padding: 10px;
  background: hsl(var(--muted) / 35%);
  border-radius: 4px;
}

.detail-section,
.gate-reason {
  margin-top: 16px;
}

.detail-section h4 {
  margin-bottom: 10px;
  font-size: 13px;
}

.gate-summary {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 14px;
}

.gate-reason p {
  margin: 6px 0 0;
  line-height: 1.65;
}

@media (max-width: 1000px) {
  .risk-detail-grid {
    grid-template-columns: 1fr;
    padding-left: 16px;
  }
}

@media (max-width: 640px) {
  .snapshot-summary,
  .gate-summary {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
