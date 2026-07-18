<script lang="ts" setup>
import type { AiReviewOverview, MisjudgementSample } from '#/api/stock';

import { computed, onMounted, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  List,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import {
  createCandidateFromMisjudgement,
  getAiMisjudgements,
  getAiReviewOverview,
} from '#/api/stock';

const loading = ref(false);
const actionLoading = ref(false);
const reviewDate = ref('2026-06-20');
const overview = ref<AiReviewOverview>();
const selectedSample = ref<MisjudgementSample>();

const selectedSuggestion = computed(() => overview.value?.candidateSuggestion);

async function loadReview() {
  loading.value = true;
  try {
    const [summary, samples] = await Promise.all([
      getAiReviewOverview(reviewDate.value),
      getAiMisjudgements({ date: reviewDate.value }),
    ]);
    overview.value = { ...summary, misjudgements: samples };
    selectedSample.value = samples[0];
  } finally {
    loading.value = false;
  }
}

async function generateCandidate() {
  if (!selectedSample.value) {
    return;
  }
  actionLoading.value = true;
  try {
    const suggestion = await createCandidateFromMisjudgement(
      selectedSample.value.sampleId,
    );
    overview.value = overview.value
      ? { ...overview.value, candidateSuggestion: suggestion }
      : overview.value;
  } finally {
    actionLoading.value = false;
  }
}

function selectSampleRow(record: Record<string, any>) {
  return {
    onClick: () => {
      selectedSample.value = record as MisjudgementSample;
    },
  };
}

onMounted(loadReview);
</script>

<template>
  <Page
    description="基于预测结果与市场上下文做结构化误判归因，并形成候选规则建议。"
    title="AI 复盘"
  >
    <Alert
      v-if="overview?.riskDisclaimer"
      :message="overview.riskDisclaimer"
      class="mb-4"
      show-icon
      type="warning"
    />

    <div class="mb-4 flex justify-end">
      <Space>
        <DatePicker v-model:value="reviewDate" value-format="YYYY-MM-DD" />
        <Button :loading="loading" type="primary" @click="loadReview">
          查询复盘
        </Button>
      </Space>
    </div>

    <Row :gutter="[16, 16]" class="mb-4">
      <Col
        v-for="metric in overview?.metrics ?? []"
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

    <Row :gutter="[16, 16]" class="mb-4">
      <Col :lg="10" :xs="24">
        <Card size="small" title="错误类型聚类">
          <List :data-source="overview?.errorClusters ?? []" size="small">
            <template #renderItem="{ item }">
              <List.Item>
                <List.Item.Meta :title="item.reasonCategory" />
                <div class="w-56">
                  <Progress :percent="Math.round(item.ratio)" size="small" />
                </div>
                <Tag color="blue">{{ item.count }}</Tag>
              </List.Item>
            </template>
          </List>
        </Card>
      </Col>
      <Col :lg="14" :xs="24">
        <Card size="small" title="误判样本列表">
          <Table
            :columns="[
              { title: '股票', dataIndex: 'symbol' },
              { title: '预测日', dataIndex: 'predictionDate' },
              { title: '预测信号', dataIndex: 'predictedSignal' },
              { title: '实际收益', dataIndex: 'actualReturn' },
              { title: '原因分类', dataIndex: 'reasonCategory' },
              { title: '触发规则', dataIndex: 'triggeredRule' },
            ]"
            :data-source="overview?.misjudgements ?? []"
            :loading="loading"
            :pagination="{ pageSize: 6 }"
            row-key="sampleId"
            size="small"
            @row="selectSampleRow"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.dataIndex === 'actualReturn'">
                <span class="text-red-500">{{ record.actualReturn }}%</span>
              </template>
              <template v-else-if="column.dataIndex === 'predictedSignal'">
                <Tag color="green">{{ record.predictedSignal }}</Tag>
              </template>
            </template>
          </Table>
        </Card>
      </Col>
    </Row>

    <Row :gutter="[16, 16]">
      <Col :lg="12" :xs="24">
        <Card size="small" title="AI 诊断">
          <template v-if="selectedSample">
            <Typography.Title :level="5">
              {{ selectedSample.symbol }}
              <Tag color="red">{{ selectedSample.actualReturn }}%</Tag>
            </Typography.Title>
            <Typography.Paragraph>
              弱势市场背景下，规则仍把突破与放量识别为偏多信号，属于需要通过市场环境和量能确认过滤的误判样本。
            </Typography.Paragraph>
            <Space wrap>
              <Tag color="blue">{{ selectedSample.reasonCategory }}</Tag>
              <Tag color="geekblue">{{ selectedSample.triggeredRule }}</Tag>
            </Space>
          </template>
        </Card>
      </Col>
      <Col :lg="12" :xs="24">
        <Card size="small" title="候选规则建议">
          <template v-if="selectedSuggestion">
            <Typography.Text type="secondary">旧条件</Typography.Text>
            <div
              class="mt-2 whitespace-pre-wrap rounded bg-black/5 p-3 font-mono text-xs"
            >
              {{ selectedSuggestion.oldCondition }}
            </div>
            <Typography.Text type="secondary">新条件</Typography.Text>
            <div
              class="mt-2 whitespace-pre-wrap rounded bg-green-500/10 p-3 font-mono text-xs"
            >
              {{ selectedSuggestion.proposedCondition }}
            </div>
            <Space class="mt-3">
              <Tag color="purple">{{ selectedSuggestion.actionRequired }}</Tag>
              <Tag color="red">{{ selectedSuggestion.priority }}</Tag>
            </Space>
          </template>
          <div class="mt-4">
            <Space>
              <Button
                :loading="actionLoading"
                type="primary"
                @click="generateCandidate"
              >
                生成候选规则
              </Button>
              <Button>提交回测</Button>
              <Button>忽略建议</Button>
              <Button>标记为数据问题</Button>
            </Space>
          </div>
        </Card>
      </Col>
    </Row>
  </Page>
</template>
