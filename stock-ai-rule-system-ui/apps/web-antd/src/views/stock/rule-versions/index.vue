<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type { RuleVersion } from '#/api/stock';

import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';

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

import { getRuleVersions, rollbackRuleVersion } from '#/api/stock';

import RiskAlert from '../components/risk-alert.vue';

const route = useRoute();
const ruleCode = computed(() => String(route.params.ruleCode || ''));
const loading = ref(false);
const saving = ref(false);
const rollbackOpen = ref(false);
const selectedVersion = ref<RuleVersion>();
const rows = ref<RuleVersion[]>([]);

const rollbackForm = reactive({
  operator: 'operator',
  reason: '',
});

const columns: TableColumnsType<RuleVersion> = [
  { dataIndex: 'versionNo', key: 'versionNo', title: '版本号', width: 110 },
  {
    dataIndex: 'approvalStatus',
    key: 'approvalStatus',
    title: '状态',
    width: 120,
  },
  { dataIndex: 'source', key: 'source', title: '来源', width: 150 },
  { dataIndex: 'changeReason', key: 'changeReason', title: '变更原因' },
  { dataIndex: 'createdBy', key: 'createdBy', title: '创建人', width: 120 },
  {
    dataIndex: 'createdTime',
    key: 'createdTime',
    title: '创建时间',
    width: 170,
  },
  {
    dataIndex: 'publishedTime',
    key: 'publishedTime',
    title: '发布时间',
    width: 170,
  },
  { key: 'actions', title: '操作', width: 120 },
];

const statusMeta: Record<string, { color: string; label: string }> = {
  approved: { color: 'cyan', label: '已审核' },
  pending: { color: 'gold', label: '待审核' },
  published: { color: 'green', label: '已发布' },
  rejected: { color: 'red', label: '已拒绝' },
  rolled_back: { color: 'default', label: '已回滚' },
};

async function loadVersions() {
  loading.value = true;
  try {
    const result = await getRuleVersions(ruleCode.value);
    rows.value = result.rows;
  } finally {
    loading.value = false;
  }
}

function openRollback(record: Record<string, any>) {
  const version = record as RuleVersion;
  selectedVersion.value = version;
  rollbackForm.reason = `回滚到 ${version.versionNo}`;
  rollbackOpen.value = true;
}

async function submitRollback() {
  if (!selectedVersion.value) {
    return;
  }
  saving.value = true;
  try {
    const version = await rollbackRuleVersion(
      ruleCode.value,
      selectedVersion.value.id,
      {
        operator: rollbackForm.operator,
        reason: rollbackForm.reason,
      },
    );
    const index = rows.value.findIndex((item) => item.id === version.id);
    if (index === -1) {
      rows.value.unshift(version);
    } else {
      rows.value[index] = version;
    }
    rollbackOpen.value = false;
    message.success('规则版本已提交回滚，生产使用仍需完成复核');
  } catch (error) {
    message.error(error instanceof Error ? error.message : '规则版本回滚失败');
  } finally {
    saving.value = false;
  }
}

onMounted(loadVersions);
</script>

<template>
  <Page
    :description="`查看 ${ruleCode} 的规则版本、发布状态和回滚入口。`"
    title="规则版本"
  >
    <RiskAlert />

    <Card size="small">
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        row-key="id"
        size="small"
      >
        <template #expandedRowRender="{ record }">
          <Typography.Text type="secondary">规则内容</Typography.Text>
          <div class="mt-2 whitespace-pre-wrap rounded bg-gray-100 p-3 text-xs">
            {{ record.ruleContent }}
          </div>
        </template>
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'approvalStatus'">
            <Tag :color="statusMeta[record.approvalStatus]?.color">
              {{
                statusMeta[record.approvalStatus]?.label ||
                record.approvalStatus
              }}
            </Tag>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space>
              <Button size="small" type="link" @click="openRollback(record)">
                回滚
              </Button>
            </Space>
          </template>
        </template>
      </Table>
    </Card>

    <Modal
      v-model:open="rollbackOpen"
      :confirm-loading="saving"
      title="回滚规则版本"
      @ok="submitRollback"
    >
      <Form :model="rollbackForm" layout="vertical">
        <Form.Item label="版本">
          <Input :value="selectedVersion?.versionNo" disabled />
        </Form.Item>
        <Form.Item label="操作人">
          <Input v-model:value="rollbackForm.operator" />
        </Form.Item>
        <Form.Item label="回滚原因">
          <Input.TextArea v-model:value="rollbackForm.reason" :rows="4" />
        </Form.Item>
      </Form>
    </Modal>
  </Page>
</template>
