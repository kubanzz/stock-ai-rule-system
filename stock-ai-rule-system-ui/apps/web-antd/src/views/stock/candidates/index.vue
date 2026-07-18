<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type { CandidateRule, CandidateRuleStatus } from '#/api/stock';

import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Form,
  Input,
  message,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import {
  getCandidateRules,
  publishCandidateRule,
  updateCandidateRuleStatus,
} from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const router = useRouter();
const loading = ref(false);
const reviewOpen = ref(false);
const reviewSaving = ref(false);
const reviewAction = ref<'approved' | 'published' | 'rejected'>('approved');
const selectedCandidate = ref<CandidateRule>();
const rows = ref<CandidateRule[]>([]);
const reviewForm = ref({
  operator: 'operator',
  reason: '',
});

const columns: TableColumnsType<CandidateRule> = [
  {
    dataIndex: 'candidateCode',
    key: 'candidateCode',
    title: '候选编码',
    width: 170,
  },
  {
    dataIndex: 'targetRuleCode',
    key: 'targetRuleCode',
    title: '目标规则',
    width: 190,
  },
  { dataIndex: 'changeType', key: 'changeType', title: '变更类型', width: 130 },
  { dataIndex: 'reason', key: 'reason', title: 'AI 原因' },
  { dataIndex: 'status', key: 'status', title: '状态', width: 110 },
  { dataIndex: 'backtestResult', key: 'backtestResult', title: '回测状态' },
  { key: 'actions', title: '操作', width: 300 },
];

const statusMeta: Partial<
  Record<CandidateRuleStatus, { color: string; label: string }>
> = {
  active: { color: 'green', label: '已上线' },
  approved: { color: 'cyan', label: '已审核' },
  archived: { color: 'default', label: '已归档' },
  backtested: { color: 'purple', label: '已回测' },
  backtesting: { color: 'purple', label: '回测中' },
  candidate: { color: 'blue', label: '候选' },
  disabled: { color: 'red', label: '已停用' },
  draft: { color: 'gold', label: '草稿' },
  generated: { color: 'blue', label: '已生成' },
  paper_trade: { color: 'geekblue', label: '模拟盘' },
  pending_review: { color: 'orange', label: '待审核' },
  published: { color: 'green', label: '已发布' },
  rejected: { color: 'red', label: '已拒绝' },
  validated: { color: 'cyan', label: '已校验' },
};

const nextStatusMap: Partial<Record<CandidateRuleStatus, CandidateRuleStatus>> =
  {
    backtested: 'pending_review',
    backtesting: 'paper_trade',
    candidate: 'backtesting',
    generated: 'validated',
    paper_trade: 'approved',
    pending_review: 'approved',
    validated: 'backtested',
  };

async function loadCandidates() {
  loading.value = true;
  try {
    const result = await getCandidateRules();
    rows.value = result.rows;
  } finally {
    loading.value = false;
  }
}

function openBacktest(record: Record<string, any>) {
  const candidate = record as CandidateRule;
  router.push({
    name: 'StockBacktests',
    query: {
      objectCode: candidate.candidateCode,
      objectType: 'candidate_rule',
    },
  });
}

function getNextStatus(status: CandidateRuleStatus) {
  return nextStatusMap[status];
}

function getStatusMeta(status: CandidateRuleStatus) {
  return statusMeta[status] ?? { color: 'default', label: status };
}

function canReview(status: CandidateRuleStatus) {
  return !['disabled', 'published', 'rejected'].includes(status);
}

function openReview(
  record: Record<string, any>,
  action: 'approved' | 'published' | 'rejected',
) {
  const candidate = record as CandidateRule;
  selectedCandidate.value = candidate;
  reviewAction.value = action;
  reviewForm.value = {
    operator: 'operator',
    reason:
      action === 'published'
        ? `发布候选规则 ${candidate.candidateCode}`
        : `${action === 'approved' ? '审核通过' : '拒绝'}：${candidate.reason}`,
  };
  reviewOpen.value = true;
}

function replaceCandidate(candidate: CandidateRule) {
  const index = rows.value.findIndex(
    (item) => item.candidateCode === candidate.candidateCode,
  );
  if (index !== -1) {
    rows.value[index] = candidate;
  }
}

async function submitReview() {
  if (!selectedCandidate.value) {
    return;
  }
  reviewSaving.value = true;
  try {
    if (reviewAction.value === 'published') {
      await publishCandidateRule(selectedCandidate.value.candidateCode, {
        operator: reviewForm.value.operator,
        reason: reviewForm.value.reason,
      });
      replaceCandidate({
        ...selectedCandidate.value,
        approvalStatus: 'published',
        status: 'published',
      });
      message.success('候选规则已发布为规则版本，仍需持续回测复核');
    } else {
      const updated = await updateCandidateRuleStatus(
        selectedCandidate.value.candidateCode,
        { status: reviewAction.value },
      );
      replaceCandidate(updated);
      message.success(`候选规则已${getStatusMeta(reviewAction.value).label}`);
    }
    reviewOpen.value = false;
  } finally {
    reviewSaving.value = false;
  }
}

async function transitionStatus(record: Record<string, any>) {
  const candidate = record as CandidateRule;
  const nextStatus = getNextStatus(candidate.status);
  if (!nextStatus) {
    return;
  }
  const updated = await updateCandidateRuleStatus(candidate.candidateCode, {
    status: nextStatus,
  });
  const index = rows.value.findIndex(
    (item) => item.candidateCode === updated.candidateCode,
  );
  if (index !== -1) {
    rows.value[index] = updated;
  }
  message.success(`候选规则已流转为${getStatusMeta(nextStatus).label}`);
}

onMounted(loadCandidates);
</script>

<template>
  <Page
    description="AI 生成的候选规则池，只能进入回测、模拟盘和人工审核流程。"
    title="候选规则"
  >
    <RiskAlert />

    <Card size="small">
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="candidateCode"
        size="small"
      >
        <template #expandedRowRender="{ record }">
          <div class="grid gap-3 md:grid-cols-2">
            <div>
              <Typography.Text type="secondary">原规则</Typography.Text>
              <div
                class="mt-2 whitespace-pre-wrap rounded bg-gray-100 p-3 font-mono text-xs"
              >
                {{ record.originalContent }}
              </div>
            </div>
            <div>
              <Typography.Text type="secondary">候选规则</Typography.Text>
              <div
                class="mt-2 whitespace-pre-wrap rounded bg-gray-100 p-3 font-mono text-xs"
              >
                {{ record.proposedContent }}
              </div>
            </div>
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="getStatusMeta(record.status).color">
              {{ getStatusMeta(record.status).label }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <Button size="small" type="link" @click="openBacktest(record)">
                回测
              </Button>
              <Button
                v-if="getNextStatus(record.status)"
                size="small"
                type="link"
                @click="transitionStatus(record)"
              >
                流转
              </Button>
              <Button
                v-if="canReview(record.status)"
                size="small"
                type="link"
                @click="openReview(record, 'approved')"
              >
                通过
              </Button>
              <Button
                v-if="canReview(record.status)"
                size="small"
                type="link"
                @click="openReview(record, 'rejected')"
              >
                拒绝
              </Button>
              <Button
                v-if="record.status === 'approved'"
                size="small"
                type="link"
                @click="openReview(record, 'published')"
              >
                发布
              </Button>
            </Space>
          </template>
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="reviewOpen"
      :confirm-loading="reviewSaving"
      title="候选规则审核"
      @ok="submitReview"
    >
      <Form :model="reviewForm" layout="vertical">
        <Form.Item label="候选编码">
          <Input :value="selectedCandidate?.candidateCode" disabled />
        </Form.Item>
        <Form.Item label="操作人">
          <Input v-model:value="reviewForm.operator" />
        </Form.Item>
        <Form.Item label="审核/发布原因">
          <Input.TextArea v-model:value="reviewForm.reason" :rows="4" />
        </Form.Item>
      </Form>
    </Modal>
  </Page>
</template>
