<script lang="ts" setup>
import type {
  RuleGovernanceDetail,
  RuleGovernanceOverview,
  RuleSummary,
} from '#/api/stock';

import { onMounted, reactive, ref } from 'vue';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Col,
  Drawer,
  Form,
  Input,
  List,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import { getRuleGovernance, getRuleGovernanceDetail } from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const loading = ref(false);
const detailLoading = ref(false);
const drawerOpen = ref(false);
const overview = ref<RuleGovernanceOverview>();
const selectedRule = ref<RuleGovernanceDetail>();

const filters = reactive({
  keyword: '',
  ruleType: undefined as string | undefined,
  status: undefined as string | undefined,
});

const columns = [
  { title: '规则编号', dataIndex: 'ruleCode', width: 180 },
  { title: '规则名称', dataIndex: 'ruleName' },
  { title: '类型', dataIndex: 'ruleType', width: 96 },
  { title: '版本', dataIndex: 'version', width: 90 },
  { title: '状态', dataIndex: 'status', width: 96 },
  { title: '触发次数(30日)', dataIndex: 'triggerCount30d' },
  { title: '5日胜率', dataIndex: 'winRate5d' },
  { title: '平均收益', dataIndex: 'avgReturn' },
  { title: '最大回撤', dataIndex: 'maxDrawdown' },
  { title: '操作', key: 'actions', width: 96 },
];

const ruleTypeOptions = [
  { label: '技术', value: 'technical' },
  { label: '趋势', value: 'trend' },
  { label: '风险', value: 'risk' },
  { label: '情绪', value: 'sentiment' },
];

const statusOptions = [
  { label: '已上线', value: 'active' },
  { label: '候选中', value: 'candidate' },
  { label: '回测中', value: 'backtesting' },
  { label: '已停用', value: 'disabled' },
];

async function loadRules() {
  loading.value = true;
  try {
    overview.value = await getRuleGovernance({
      ruleType: filters.ruleType,
      status: filters.status,
    });
  } finally {
    loading.value = false;
  }
}

async function openDetail(record: Record<string, any>) {
  const rule = record as RuleSummary;
  drawerOpen.value = true;
  detailLoading.value = true;
  try {
    selectedRule.value = await getRuleGovernanceDetail(rule.ruleCode);
  } finally {
    detailLoading.value = false;
  }
}

function statusColor(status: string) {
  if (status === 'active' || status === 'approved') return 'green';
  if (status === 'backtesting' || status === 'candidate') return 'blue';
  if (status === 'disabled') return 'default';
  return 'gold';
}

onMounted(loadRules);
</script>

<template>
  <Page description="统一管理规则版本、状态、表现与候选变更。" title="规则治理">
    <RiskAlert :message="overview?.riskDisclaimer" />

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

    <Card class="mb-4" size="small">
      <Form layout="inline">
        <Form.Item>
          <Input
            v-model:value="filters.keyword"
            allow-clear
            placeholder="搜索规则名称/编号/描述"
          />
        </Form.Item>
        <Form.Item label="规则类型">
          <Select
            v-model:value="filters.ruleType"
            :options="ruleTypeOptions"
            allow-clear
            class="w-32"
          />
        </Form.Item>
        <Form.Item label="状态">
          <Select
            v-model:value="filters.status"
            :options="statusOptions"
            allow-clear
            class="w-32"
          />
        </Form.Item>
        <Form.Item>
          <Space>
            <Button type="primary" @click="loadRules">查询</Button>
            <Button>新建规则</Button>
          </Space>
        </Form.Item>
      </Form>
    </Card>

    <Card size="small">
      <Table
        :columns="columns"
        :data-source="overview?.rules ?? []"
        :loading="loading"
        :pagination="{ pageSize: 10 }"
        row-key="ruleCode"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'ruleCode'">
            <Typography.Link @click="openDetail(record)">
              {{ record.ruleCode }}
            </Typography.Link>
          </template>
          <template v-else-if="column.dataIndex === 'status'">
            <Tag :color="statusColor(record.status)">{{ record.status }}</Tag>
          </template>
          <template v-else-if="column.dataIndex === 'winRate5d'">
            {{ record.winRate5d }}%
          </template>
          <template v-else-if="column.dataIndex === 'avgReturn'">
            <span class="text-green-500">{{ record.avgReturn }}%</span>
          </template>
          <template v-else-if="column.dataIndex === 'maxDrawdown'">
            <span class="text-red-500">{{ record.maxDrawdown }}%</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Button size="small" type="link" @click="openDetail(record)">
              详情
            </Button>
          </template>
        </template>
      </Table>
    </Card>

    <Drawer
      v-model:open="drawerOpen"
      :loading="detailLoading"
      placement="right"
      title="规则详情"
      width="560"
    >
      <template v-if="selectedRule">
        <Space direction="vertical" class="w-full" size="middle">
          <Card size="small">
            <Typography.Title :level="5">
              {{ selectedRule.ruleName }}
            </Typography.Title>
            <Space wrap>
              <Tag color="blue">{{ selectedRule.ruleCode }}</Tag>
              <Tag :color="statusColor(selectedRule.status)">
                {{ selectedRule.status }}
              </Tag>
              <Tag>{{ selectedRule.version }}</Tag>
            </Space>
            <Typography.Paragraph class="mt-3 mb-0">
              {{ selectedRule.description }}
            </Typography.Paragraph>
          </Card>

          <Card size="small" title="原始规则表达式">
            <Typography.Text code>
              {{ selectedRule.expression }}
            </Typography.Text>
          </Card>

          <Card size="small" title="相关因子">
            <Space wrap>
              <Tag
                v-for="factor in selectedRule.relatedFactors"
                :key="factor"
                color="geekblue"
              >
                {{ factor }}
              </Tag>
            </Space>
          </Card>

          <Card size="small" title="表现概览">
            <Row :gutter="[12, 12]">
              <Col
                v-for="metric in selectedRule.performance"
                :key="metric.label"
                :span="12"
              >
                <Statistic
                  :suffix="metric.unit"
                  :title="metric.label"
                  :value="metric.value"
                />
              </Col>
            </Row>
          </Card>

          <Card size="small" title="候选变更">
            <List
              :data-source="selectedRule.candidateDiff?.highlights ?? []"
              size="small"
            >
              <template #renderItem="{ item }">
                <List.Item>
                  <Tag color="purple">候选</Tag>
                  {{ item }}
                </List.Item>
              </template>
            </List>
            <Row :gutter="[12, 12]" class="mt-3">
              <Col :span="12">
                <Typography.Text type="secondary">当前版本</Typography.Text>
                <div
                  class="mt-2 whitespace-pre-wrap rounded bg-black/5 p-3 font-mono text-xs"
                >
                  {{ selectedRule.candidateDiff?.currentContent }}
                </div>
              </Col>
              <Col :span="12">
                <Typography.Text type="secondary">拟变更版本</Typography.Text>
                <div
                  class="mt-2 whitespace-pre-wrap rounded bg-green-500/10 p-3 font-mono text-xs"
                >
                  {{
                    selectedRule.candidateDiff?.proposedContent ||
                    '暂无候选变更'
                  }}
                </div>
              </Col>
            </Row>
          </Card>

          <Space>
            <Button type="primary">回测</Button>
            <Button>审核</Button>
            <Button danger>停用</Button>
          </Space>
        </Space>
      </template>
    </Drawer>
  </Page>
</template>
