<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type { RuleDefinition, RuleDefinitionUpsert } from '#/api/stock';

import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';

import { Page } from '@vben/common-ui';

import {
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from 'ant-design-vue';

import {
  createRule,
  disableRule,
  enableRule,
  getRules,
  updateRule,
} from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const router = useRouter();
const loading = ref(false);
const saving = ref(false);
const modalOpen = ref(false);
const editingCode = ref('');
const rows = ref<RuleDefinition[]>([]);

const formState = reactive<RuleDefinitionUpsert>({
  priority: 50,
  ruleCode: '',
  ruleContent: '',
  ruleFormat: 'json',
  ruleName: '',
  ruleType: 'trend',
  status: 'draft',
  version: 'v1.0',
});

const columns: TableColumnsType<RuleDefinition> = [
  { dataIndex: 'ruleCode', key: 'ruleCode', title: '规则编码', width: 190 },
  { dataIndex: 'ruleName', key: 'ruleName', title: '规则名称' },
  { dataIndex: 'ruleType', key: 'ruleType', title: '类型', width: 110 },
  { dataIndex: 'ruleFormat', key: 'ruleFormat', title: '格式', width: 90 },
  { dataIndex: 'status', key: 'status', title: '状态', width: 110 },
  { dataIndex: 'priority', key: 'priority', title: '优先级', width: 90 },
  { dataIndex: 'version', key: 'version', title: '版本', width: 90 },
  {
    dataIndex: 'updatedTime',
    key: 'updatedTime',
    title: '更新时间',
    width: 160,
  },
  { key: 'actions', title: '操作', width: 270 },
];

const statusMeta: Record<string, { color: string; label: string }> = {
  active: { color: 'green', label: '已启用' },
  approved: { color: 'cyan', label: '已审核' },
  archived: { color: 'default', label: '已归档' },
  backtesting: { color: 'purple', label: '回测中' },
  candidate: { color: 'blue', label: '候选' },
  disabled: { color: 'red', label: '已停用' },
  draft: { color: 'gold', label: '草稿' },
  paper_trade: { color: 'geekblue', label: '模拟盘' },
};

async function loadRules() {
  loading.value = true;
  try {
    const result = await getRules();
    rows.value = result.rows;
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  Object.assign(formState, {
    priority: 50,
    ruleCode: '',
    ruleContent: '',
    ruleFormat: 'json',
    ruleName: '',
    ruleType: 'trend',
    status: 'draft',
    version: 'v1.0',
  });
}

function openCreate() {
  editingCode.value = '';
  resetForm();
  modalOpen.value = true;
}

function openEdit(record: Record<string, any>) {
  const rule = record as RuleDefinition;
  editingCode.value = rule.ruleCode;
  Object.assign(formState, {
    priority: rule.priority,
    ruleCode: rule.ruleCode,
    ruleContent: rule.ruleContent,
    ruleFormat: rule.ruleFormat,
    ruleName: rule.ruleName,
    ruleType: rule.ruleType,
    status: rule.status,
    version: rule.version,
  });
  modalOpen.value = true;
}

function openVersions(record: Record<string, any>) {
  const rule = record as RuleDefinition;
  router.push({
    name: 'StockRuleVersions',
    params: { ruleCode: rule.ruleCode },
  });
}

async function saveRule() {
  saving.value = true;
  try {
    const saved = editingCode.value
      ? await updateRule(editingCode.value, formState)
      : await createRule(formState);
    const index = rows.value.findIndex(
      (item) => item.ruleCode === saved.ruleCode,
    );
    if (index === -1) {
      rows.value.unshift(saved);
    } else {
      rows.value[index] = saved;
    }
    modalOpen.value = false;
    message.success('规则已保存，生产启用前仍需回测与人工审核');
  } finally {
    saving.value = false;
  }
}

async function toggleRule(record: Record<string, any>) {
  const rule = record as RuleDefinition;
  if (rule.status === 'active') {
    await disableRule(rule.ruleCode);
    rule.status = 'disabled';
    message.success('规则已停用');
    return;
  }
  await enableRule(rule.ruleCode);
  rule.status = 'active';
  message.success('规则已启用');
}

onMounted(loadRules);
</script>

<template>
  <Page
    description="规则新增、编辑、启用和停用；候选规则不能绕过回测与审核直接上线。"
    title="规则管理"
  >
    <RiskAlert />

    <Card size="small">
      <div class="mb-3 flex justify-end">
        <Button type="primary" @click="openCreate">新增规则</Button>
      </div>

      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="ruleCode"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'ruleCode'">
            <div class="font-medium">{{ record.ruleCode }}</div>
            <div class="text-xs text-gray-500">{{ record.ruleContent }}</div>
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="statusMeta[record.status]?.color">
              {{ statusMeta[record.status]?.label || record.status }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <Button size="small" type="link" @click="openEdit(record)">
                编辑
              </Button>
              <Button size="small" type="link" @click="openVersions(record)">
                版本
              </Button>
              <Button size="small" type="link" @click="toggleRule(record)">
                {{ record.status === 'active' ? '停用' : '启用' }}
              </Button>
            </Space>
          </template>
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="modalOpen"
      :confirm-loading="saving"
      title="规则配置"
      width="760px"
      @ok="saveRule"
    >
      <Form :model="formState" layout="vertical">
        <Form.Item label="规则编码">
          <Input v-model:value="formState.ruleCode" placeholder="R_TREND_001" />
        </Form.Item>
        <Form.Item label="规则名称">
          <Input
            v-model:value="formState.ruleName"
            placeholder="强势突破看涨"
          />
        </Form.Item>
        <Space class="w-full" size="middle">
          <Form.Item label="类型">
            <Input v-model:value="formState.ruleType" placeholder="trend" />
          </Form.Item>
          <Form.Item label="格式">
            <Select
              v-model:value="formState.ruleFormat"
              :options="[
                { label: 'JSON', value: 'json' },
                { label: 'Drools', value: 'drools' },
              ]"
              class="w-32"
            />
          </Form.Item>
          <Form.Item label="状态">
            <Select
              v-model:value="formState.status"
              :options="
                Object.entries(statusMeta).map(([value, item]) => ({
                  label: item.label,
                  value,
                }))
              "
              class="w-32"
            />
          </Form.Item>
          <Form.Item label="优先级">
            <InputNumber v-model:value="formState.priority" :min="0" />
          </Form.Item>
        </Space>
        <Form.Item label="版本">
          <Input v-model:value="formState.version" placeholder="v1.0" />
        </Form.Item>
        <Form.Item label="规则内容">
          <Input.TextArea
            v-model:value="formState.ruleContent"
            :rows="5"
            placeholder="short_term_trend = strong_up AND volume_status = abnormal_high"
          />
        </Form.Item>
      </Form>
    </Modal>
  </Page>
</template>
