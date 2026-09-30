<script lang="ts" setup>
import type {
  RuleGovernanceDetail,
  RuleGovernanceOverview,
  RuleSummary,
} from '#/api/stock';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  Col,
  Drawer,
  Form,
  Input,
  List,
  message,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'ant-design-vue';

import {
  disableRule,
  getRuleGovernance,
  getRuleGovernanceDetail,
} from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';
import { getRuleTypeLabel, ruleTypeOptions } from '../rule-type';
import RuleGroups from './rule-groups.vue';
import RuleStrategies from './rule-strategies.vue';

const loading = ref(false);
const activeSection = ref('rules');
const detailLoading = ref(false);
const drawerOpen = ref(false);
const overview = ref<RuleGovernanceOverview>();
const selectedRule = ref<RuleGovernanceDetail>();
const router = useRouter();

const filters = reactive({
  execution: 'production' as 'all' | 'inactive' | 'production',
  keyword: '',
  ruleType: undefined as string | undefined,
  status: undefined as string | undefined,
});

const columns = [
  { title: '规则编号', dataIndex: 'ruleCode', width: 260 },
  { title: '规则名称', dataIndex: 'ruleName', width: 280 },
  { title: '规则详情', dataIndex: 'description', width: 440 },
  { title: '类型', dataIndex: 'ruleType', width: 110 },
  { title: '格式', dataIndex: 'ruleFormat', width: 86 },
  { title: '版本', dataIndex: 'version', width: 90 },
  { title: '状态', dataIndex: 'status', width: 96 },
  { title: '执行状态', dataIndex: 'productionExecutable', width: 130 },
  { title: '触发次数(30日)', dataIndex: 'triggerCount30d' },
  { title: '5日胜率', dataIndex: 'winRate5d' },
  { title: '平均收益', dataIndex: 'avgReturn' },
  { title: '最大回撤', dataIndex: 'maxDrawdown' },
  { title: '操作', key: 'actions', width: 96 },
];

const statusOptions = [
  { label: '已上线', value: 'active' },
  { label: '已停用', value: 'disabled' },
  { label: '草稿', value: 'draft' },
];

const statusLabels: Record<string, string> = {
  active: '已上线',
  approved: '已审核',
  archived: '已归档',
  backtesting: '回测中',
  candidate: '候选中',
  disabled: '已停用',
  draft: '草稿',
  paper_trade: '模拟盘',
};

const executionOptions = [
  { label: '生产执行中', value: 'production' },
  { label: '未参与生产', value: 'inactive' },
  { label: '全部规则', value: 'all' },
];

type RuleExecutionState = Pick<
  RuleGovernanceDetail,
  'enabled' | 'productionExecutable' | 'ruleFormat' | 'status'
>;

function isProductionExecutable(rule: Partial<RuleExecutionState>): boolean {
  // The backend is authoritative. The fallback keeps older responses safe:
  // legacy enabled=null rows are executable only when active Drools rules.
  return (
    rule.productionExecutable ??
    (rule.ruleFormat === 'drools' &&
      rule.status === 'active' &&
      rule.enabled !== false)
  );
}

function getExecutionLabel(rule: Partial<RuleExecutionState>): string {
  return isProductionExecutable(rule) ? '生产执行中' : '未参与生产';
}

function governanceMetricLabel(label: string): string {
  if (label === '候选规则') return '候选记录';
  if (label === '已上线') return '生产执行中';
  return label;
}

const governanceMetrics = computed(() => {
  const rules = overview.value?.rules ?? [];
  const disabledCount = rules.filter(
    (rule) => rule.status === 'disabled',
  ).length;
  return [
    ...(overview.value?.metrics ?? []).map((metric) => ({
      ...metric,
      label: governanceMetricLabel(metric.label),
    })),
    { label: '已停用', tone: 'default', unit: '条', value: disabledCount },
  ];
});

const visibleRules = computed(() => {
  const keyword = filters.keyword.trim().toLowerCase();
  return (overview.value?.rules ?? []).filter((rule) => {
    const matchesKeyword =
      !keyword ||
      [rule.ruleCode, rule.ruleName, rule.description]
        .filter(Boolean)
        .some((value) => value?.toLowerCase().includes(keyword));
    const matchesExecution =
      filters.execution === 'all' ||
      (filters.execution === 'production' && isProductionExecutable(rule)) ||
      (filters.execution === 'inactive' && !isProductionExecutable(rule));
    return matchesKeyword && matchesExecution;
  });
});

function getStatusLabel(status: string): string {
  return statusLabels[status] ?? status;
}

async function loadRules() {
  loading.value = true;
  try {
    overview.value = await getRuleGovernance({
      ruleType: filters.ruleType,
      status: filters.status,
    });
  } catch (error) {
    message.error(error instanceof Error ? error.message : '规则治理加载失败');
  } finally {
    loading.value = false;
  }
}

async function openDetail(record: RuleSummary | Record<string, any>) {
  const rule = record as RuleSummary;
  drawerOpen.value = true;
  detailLoading.value = true;
  selectedRule.value = undefined;
  try {
    selectedRule.value = await getRuleGovernanceDetail(rule.ruleCode);
  } catch (error) {
    message.error(error instanceof Error ? error.message : '规则详情加载失败');
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

function openCreateRule() {
  router.push({ name: 'StockRules', query: { create: '1' } });
}

function openCandidates() {
  router.push({ name: 'StockCandidates' });
}

function openBacktest() {
  if (!selectedRule.value) return;
  router.push({
    name: 'StockBacktests',
    query: { objectCode: selectedRule.value.ruleCode, objectType: 'rule' },
  });
}

function confirmDisable() {
  if (!selectedRule.value || selectedRule.value.status !== 'active') return;
  const rule = selectedRule.value;
  Modal.confirm({
    content: `停用后，${rule.ruleName} 不再参与后续生产信号。`,
    okText: '确认停用',
    title: '停用正式规则',
    async onOk() {
      try {
        await disableRule(rule.ruleCode);
        drawerOpen.value = false;
        message.success('规则已停用');
        await loadRules();
      } catch (error) {
        message.error(error instanceof Error ? error.message : '规则停用失败');
        throw error;
      }
    },
  });
}

onMounted(loadRules);
</script>

<template>
  <Page
    description="正式规则按执行状态治理；候选记录经回测和人工审核后发布为正式 Drools 版本。"
    title="规则治理"
  >
    <RiskAlert :message="overview?.riskDisclaimer" />

    <Alert
      class="mt-3"
      show-icon
      type="info"
      message="正式规则与候选记录分开治理"
      description="本页只把 active Drools 且允许执行的定义标为生产执行中。候选记录本身不执行；已发布候选对应的正式 Drools 规则可能参与生产。"
    >
      <template #action>
        <Button size="small" type="link" @click="openCandidates">
          查看候选规则
        </Button>
      </template>
    </Alert>

    <Tabs v-model:active-key="activeSection" class="mt-3">
      <Tabs.TabPane key="rules" tab="单条规则" />
      <Tabs.TabPane key="groups" tab="规则组" />
      <Tabs.TabPane key="strategies" tab="应用方案" />
    </Tabs>

    <template v-if="activeSection === 'rules'">
      <Row :gutter="[16, 16]" class="mb-4">
        <Col
          v-for="metric in governanceMetrics"
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
          <Form.Item label="执行状态">
            <Select
              v-model:value="filters.execution"
              :options="executionOptions"
              class="w-36"
            />
          </Form.Item>
          <Form.Item>
            <Space>
              <Button type="primary" @click="loadRules">查询</Button>
              <Button type="primary" @click="openCreateRule"
                >新建正式规则</Button
              >
            </Space>
          </Form.Item>
        </Form>
      </Card>

      <Card size="small">
        <div class="mb-3 flex flex-wrap items-center gap-2">
          <Typography.Text type="secondary">当前目录：</Typography.Text>
          <Tag color="green">生产执行中 = active + Drools + 已启用</Tag>
          <Tag>未参与生产包含停用与草稿规则</Tag>
          <Tag color="blue">候选记录含已发布留痕</Tag>
          <Button size="small" type="link" @click="filters.execution = 'all'">
            查看全部正式规则
          </Button>
        </div>
        <Table
          :columns="columns"
          :data-source="visibleRules"
          :loading="loading"
          :pagination="{ pageSize: 10 }"
          :scroll="{ x: 2100 }"
          row-key="ruleCode"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.dataIndex === 'ruleCode'">
              <Typography.Link @click="openDetail(record)">
                {{ record.ruleCode }}
              </Typography.Link>
            </template>
            <template v-else-if="column.dataIndex === 'ruleType'">
              {{ getRuleTypeLabel(record.ruleType) }}
            </template>
            <template v-else-if="column.dataIndex === 'ruleFormat'">
              <Tag :color="record.ruleFormat === 'drools' ? 'blue' : 'default'">
                {{ record.ruleFormat || '未知' }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'status'">
              <Tag :color="statusColor(record.status)">
                {{ getStatusLabel(record.status) }}
              </Tag>
            </template>
            <template v-else-if="column.dataIndex === 'productionExecutable'">
              <Tag
                :color="isProductionExecutable(record) ? 'green' : 'default'"
              >
                {{ getExecutionLabel(record) }}
              </Tag>
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
    </template>

    <RuleGroups v-else-if="activeSection === 'groups'" />
    <RuleStrategies v-else />

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
              <Tag>{{ getRuleTypeLabel(selectedRule.ruleType) }}</Tag>
              <Tag :color="statusColor(selectedRule.status)">
                {{ getStatusLabel(selectedRule.status) }}
              </Tag>
              <Tag
                :color="
                  isProductionExecutable(selectedRule) ? 'green' : 'default'
                "
              >
                {{ getExecutionLabel(selectedRule) }}
              </Tag>
              <Tag>{{ selectedRule.ruleFormat || '未知' }}</Tag>
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
                  :suffix="metric.value == null ? undefined : metric.unit"
                  :title="metric.label"
                  :value="metric.value ?? '—'"
                />
              </Col>
            </Row>
          </Card>

          <Card size="small" title="关联候选记录（含已发布留痕）">
            <List
              :data-source="selectedRule.candidateDiff?.highlights ?? []"
              size="small"
            >
              <template #renderItem="{ item }">
                <List.Item>
                  <Tag color="purple">记录</Tag>
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
                <Typography.Text type="secondary">候选记录内容</Typography.Text>
                <div
                  class="mt-2 whitespace-pre-wrap rounded bg-green-500/10 p-3 font-mono text-xs"
                >
                  {{
                    selectedRule.candidateDiff?.proposedContent ||
                    '暂无关联候选记录'
                  }}
                </div>
              </Col>
            </Row>
          </Card>

          <Space>
            <Button type="primary" @click="openBacktest">回测</Button>
            <Button @click="openCandidates">候选审核</Button>
            <Button
              v-if="selectedRule.status === 'active'"
              danger
              @click="confirmDisable"
              >停用</Button
            >
          </Space>
        </Space>
      </template>
    </Drawer>
  </Page>
</template>
