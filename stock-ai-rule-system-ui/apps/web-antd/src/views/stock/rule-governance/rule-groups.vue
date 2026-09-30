<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type {
  CombinationCopyRequest,
  RuleGroup,
  RuleGroupUpsert,
} from '#/api/stock/rule-combinations';
import type { RuleDefinition } from '#/api/stock/types';

import { computed, onMounted, reactive, ref } from 'vue';

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
  Switch,
  Table,
  Tag,
  Typography,
} from 'ant-design-vue';

import { getRules } from '#/api/stock';
import {
  copyRuleGroup,
  createRuleGroup,
  getRuleGroup,
  getRuleGroups,
  getRuleGroupVersions,
  setRuleGroupStatus,
  updateRuleGroup,
} from '#/api/stock/rule-combinations';

const loading = ref(false);
const saving = ref(false);
const editorOpen = ref(false);
const copyOpen = ref(false);
const historyOpen = ref(false);
const historyCode = ref('');
const historyRows = ref<RuleGroup[]>([]);
const editingCode = ref('');
const copyingCode = ref('');
const rows = ref<RuleGroup[]>([]);
const rules = ref<RuleDefinition[]>([]);

const form = reactive<RuleGroupUpsert>({
  aggregation: 'WEIGHTED',
  description: '',
  groupCode: '',
  groupName: '',
  members: [],
  minMatchedRules: 1,
});
const copyForm = reactive<CombinationCopyRequest>({ newCode: '', newName: '' });

const columns: TableColumnsType<RuleGroup> = [
  { title: '规则组编码', dataIndex: 'groupCode', width: 190 },
  { title: '名称', dataIndex: 'groupName' },
  { title: '聚合方式', dataIndex: 'aggregation', width: 120 },
  { title: '成员规则', dataIndex: 'members', width: 110 },
  { title: '版本', dataIndex: 'version', width: 80 },
  { title: '状态', dataIndex: 'status', width: 100 },
  { title: '操作', key: 'actions', width: 260 },
];

const aggregationOptions = [
  { label: '全部命中（AND）', value: 'AND' },
  { label: '任一命中（OR）', value: 'OR' },
  { label: '达到最低命中数（WEIGHTED）', value: 'WEIGHTED' },
];

const aggregationHint = computed(() => {
  if (form.aggregation === 'AND')
    return '全部成员规则命中时成立，最低命中数固定为成员数。';
  if (form.aggregation === 'OR')
    return '任意一条成员规则命中时成立，最低命中数固定为 1。';
  return '命中规则数达到下方门槛时成立；已命中规则按成员权重计分。';
});

function effectiveMinMatchedRules() {
  if (form.aggregation === 'AND') return form.members.length;
  if (form.aggregation === 'OR') return 1;
  return form.minMatchedRules;
}

const ruleOptions = computed(() =>
  rules.value
    .filter((rule) => rule.ruleFormat === 'drools')
    .map((rule) => ({
      label: `${rule.ruleName}（${rule.ruleCode} · ${rule.status === 'active' ? '已启用' : '未启用'}）`,
      value: rule.ruleCode,
    })),
);

async function load() {
  loading.value = true;
  try {
    const [groups, definitions] = await Promise.all([
      getRuleGroups(),
      getRules(),
    ]);
    rows.value = groups.rows;
    rules.value = definitions.rows;
  } catch (error) {
    message.error(error instanceof Error ? error.message : '规则组加载失败');
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  Object.assign(form, {
    aggregation: 'WEIGHTED',
    description: '',
    groupCode: '',
    groupName: '',
    members: [],
    minMatchedRules: 1,
  });
}

function openCreate() {
  editingCode.value = '';
  resetForm();
  editorOpen.value = true;
}

async function openEdit(record: Record<string, any>) {
  const row = record as RuleGroup;
  try {
    const detail = await getRuleGroup(row.groupCode);
    editingCode.value = row.groupCode;
    Object.assign(form, {
      aggregation: detail.aggregation,
      description: detail.description ?? '',
      groupCode: detail.groupCode,
      groupName: detail.groupName,
      members: detail.members.map((member) => ({ ...member })),
      minMatchedRules: detail.minMatchedRules,
    });
    editorOpen.value = true;
  } catch (error) {
    message.error(
      error instanceof Error ? error.message : '规则组详情加载失败',
    );
  }
}

function addMember() {
  form.members.push({ required: false, ruleCode: '', weight: 1 });
}

function validate() {
  if (!form.groupCode.trim() || !form.groupName.trim())
    return '请填写规则组编码和名称';
  if (form.members.length === 0) return '请至少选择一条规则';
  const codes = form.members.map((member) => member.ruleCode);
  if (codes.some((code) => !code)) return '请为每个成员选择规则';
  if (new Set(codes).size !== codes.length) return '同一规则不能在组内重复';
  if (
    form.members.some(
      (member) => !member.weight || member.weight <= 0 || member.weight > 100,
    )
  ) {
    return '成员权重须大于 0 且不超过 100';
  }
  if (
    form.aggregation === 'WEIGHTED' &&
    (!Number.isInteger(form.minMatchedRules) ||
      form.minMatchedRules < 1 ||
      form.minMatchedRules > form.members.length)
  ) {
    return '最低命中数量需在 1 和成员数量之间';
  }
  return '';
}

async function save() {
  const error = validate();
  if (error) {
    message.warning(error);
    return;
  }
  saving.value = true;
  try {
    const payload: RuleGroupUpsert = {
      aggregation: form.aggregation,
      description: form.description?.trim(),
      groupCode: form.groupCode.trim(),
      groupName: form.groupName.trim(),
      members: form.members.map(({ required, ruleCode, weight }) => ({
        required,
        ruleCode,
        weight,
      })),
      minMatchedRules: effectiveMinMatchedRules(),
    };
    await (editingCode.value
      ? updateRuleGroup(editingCode.value, payload)
      : createRuleGroup(payload));
    editorOpen.value = false;
    message.success('规则组已保存');
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '规则组保存失败');
  } finally {
    saving.value = false;
  }
}

function openCopy(record: Record<string, any>) {
  const row = record as RuleGroup;
  copyingCode.value = row.groupCode;
  copyForm.newCode = `${row.groupCode}_COPY`;
  copyForm.newName = `${row.groupName} 副本`;
  copyOpen.value = true;
}

async function openHistory(record: Record<string, any>) {
  const row = record as RuleGroup;
  historyCode.value = row.groupCode;
  try {
    historyRows.value = await getRuleGroupVersions(row.groupCode);
    historyOpen.value = true;
  } catch (error) {
    message.error(
      error instanceof Error ? error.message : '规则组版本加载失败',
    );
  }
}

async function copy() {
  if (!copyForm.newCode.trim() || !copyForm.newName.trim()) {
    message.warning('请填写新规则组编码和名称');
    return;
  }
  saving.value = true;
  try {
    await copyRuleGroup(copyingCode.value, {
      newCode: copyForm.newCode.trim(),
      newName: copyForm.newName.trim(),
    });
    copyOpen.value = false;
    message.success('规则组已复制为草稿');
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '规则组复制失败');
  } finally {
    saving.value = false;
  }
}

async function toggle(record: Record<string, any>) {
  const row = record as RuleGroup;
  try {
    await setRuleGroupStatus(
      row.groupCode,
      row.status === 'active' ? 'disabled' : 'active',
    );
    message.success(row.status === 'active' ? '规则组已停用' : '规则组已启用');
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '状态修改失败');
  }
}

onMounted(load);
</script>

<template>
  <Card size="small">
    <div class="mb-3 flex items-center justify-between gap-3">
      <Typography.Text type="secondary">
        将正式规则组合为可复用单元。启用前需确保成员规则可执行。
      </Typography.Text>
      <Button type="primary" @click="openCreate">新建规则组</Button>
    </div>
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      row-key="groupCode"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'aggregation'">
          {{
            aggregationOptions.find((item) => item.value === record.aggregation)
              ?.label
          }}
        </template>
        <template v-else-if="column.dataIndex === 'members'">
          {{ record.members?.length ?? 0 }} 条
        </template>
        <template v-else-if="column.dataIndex === 'status'">
          <Tag
            :color="
              record.status === 'active'
                ? 'green'
                : record.status === 'disabled'
                  ? 'default'
                  : 'gold'
            "
          >
            {{
              record.status === 'active'
                ? '已启用'
                : record.status === 'disabled'
                  ? '已停用'
                  : '草稿'
            }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <Space>
            <Button size="small" type="link" @click="openEdit(record)"
              >编辑</Button
            >
            <Button size="small" type="link" @click="openCopy(record)"
              >复制</Button
            >
            <Button size="small" type="link" @click="openHistory(record)"
              >版本</Button
            >
            <Button size="small" type="link" @click="toggle(record)">
              {{ record.status === 'active' ? '停用' : '启用' }}
            </Button>
          </Space>
        </template>
      </template>
    </Table>
  </Card>

  <Modal
    v-model:open="editorOpen"
    :confirm-loading="saving"
    :title="editingCode ? '编辑规则组' : '新建规则组'"
    width="800px"
    @ok="save"
  >
    <Form :model="form" layout="vertical">
      <Form.Item label="规则组编码" required>
        <Input
          v-model:value="form.groupCode"
          :disabled="!!editingCode"
          placeholder="G_TREND_CONFIRM"
        />
      </Form.Item>
      <Form.Item label="规则组名称" required>
        <Input v-model:value="form.groupName" placeholder="趋势确认组" />
      </Form.Item>
      <Form.Item label="说明">
        <Input.TextArea
          v-model:value="form.description"
          :rows="2"
          placeholder="说明成员规则组合的目的和风险边界"
        />
      </Form.Item>
      <Space wrap>
        <Form.Item label="聚合方式">
          <Select
            v-model:value="form.aggregation"
            :options="aggregationOptions"
            class="w-52"
          />
        </Form.Item>
        <Form.Item
          v-if="form.aggregation === 'WEIGHTED'"
          label="最低命中规则数"
        >
          <InputNumber
            v-model:value="form.minMatchedRules"
            :min="1"
            :max="Math.max(1, form.members.length)"
          />
        </Form.Item>
      </Space>
      <Typography.Paragraph class="mb-3 text-xs" type="secondary">
        {{ aggregationHint }}“必选”成员未命中时，规则组不成立。
      </Typography.Paragraph>
      <div class="mb-2 flex items-center justify-between">
        <Typography.Text strong>成员规则</Typography.Text>
        <Button size="small" @click="addMember">添加规则</Button>
      </div>
      <div
        v-for="(member, index) in form.members"
        :key="index"
        class="mb-2 flex flex-wrap items-center gap-2"
      >
        <Select
          v-model:value="member.ruleCode"
          :options="ruleOptions"
          class="min-w-72 flex-1"
          show-search
          option-filter-prop="label"
          placeholder="选择规则"
        />
        <InputNumber
          v-if="form.aggregation === 'WEIGHTED'"
          v-model:value="member.weight"
          :min="0.01"
          :max="100"
          :precision="2"
          addon-before="权重"
          class="w-36"
        />
        <span>必选</span>
        <Switch v-model:checked="member.required" />
        <Button danger size="small" @click="form.members.splice(index, 1)"
          >移除</Button
        >
      </div>
      <Typography.Text type="secondary"
        >成员权重仅在 WEIGHTED 聚合时参与计分。</Typography.Text
      >
    </Form>
  </Modal>

  <Modal
    v-model:open="copyOpen"
    :confirm-loading="saving"
    title="复制规则组"
    @ok="copy"
  >
    <Form :model="copyForm" layout="vertical">
      <Form.Item label="新规则组编码" required
        ><Input v-model:value="copyForm.newCode"
      /></Form.Item>
      <Form.Item label="新规则组名称" required
        ><Input v-model:value="copyForm.newName"
      /></Form.Item>
    </Form>
  </Modal>

  <Modal
    v-model:open="historyOpen"
    :footer="null"
    :title="`规则组版本 · ${historyCode}`"
    width="700px"
  >
    <div
      v-for="snapshot in historyRows"
      :key="snapshot.version"
      class="mb-3 rounded border p-3"
    >
      <Space wrap>
        <Tag color="blue">{{ snapshot.version }}</Tag>
        <Typography.Text strong>{{ snapshot.groupName }}</Typography.Text>
        <Tag>{{ snapshot.aggregation }}</Tag>
        <Tag>{{ snapshot.status }}</Tag>
      </Space>
      <div class="mt-2">
        <Typography.Text type="secondary">
          成员：{{
            snapshot.members
              .map(
                (item) =>
                  `${item.ruleCode}${snapshot.aggregation === 'WEIGHTED' ? ` × ${item.weight}` : ''}${item.required ? '（必选）' : ''}`,
              )
              .join('、')
          }}
        </Typography.Text>
      </div>
    </div>
  </Modal>
</template>
