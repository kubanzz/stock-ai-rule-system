<script lang="ts" setup>
import type { TableColumnsType } from 'ant-design-vue';

import type {
  CombinationCopyRequest,
  RuleGroup,
  RuleStrategy,
  RuleStrategyUpsert,
} from '#/api/stock/rule-combinations';

import { computed, onMounted, reactive, ref } from 'vue';

import {
  Alert,
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

import {
  copyRuleStrategy,
  createRuleStrategy,
  getRuleGroups,
  getRuleStrategies,
  getRuleStrategy,
  getRuleStrategyVersions,
  setRuleStrategyStatus,
  updateRuleStrategy,
} from '#/api/stock/rule-combinations';

const loading = ref(false);
const saving = ref(false);
const editorOpen = ref(false);
const copyOpen = ref(false);
const historyOpen = ref(false);
const historyCode = ref('');
const historyRows = ref<RuleStrategy[]>([]);
const editingCode = ref('');
const copyingCode = ref('');
const rows = ref<RuleStrategy[]>([]);
const groups = ref<RuleGroup[]>([]);

const form = reactive<RuleStrategyUpsert>({
  bearishThreshold: 60,
  bullishThreshold: 55,
  description: '',
  groups: [],
  riskThreshold: 80,
  strategyCode: '',
  strategyName: '',
});
const copyForm = reactive<CombinationCopyRequest>({ newCode: '', newName: '' });

const columns: TableColumnsType<RuleStrategy> = [
  { title: '方案编码', dataIndex: 'strategyCode', width: 190 },
  { title: '方案名称', dataIndex: 'strategyName' },
  { title: '规则组', dataIndex: 'groups', width: 95 },
  { title: '看涨阈值', dataIndex: 'bullishThreshold', width: 95 },
  { title: '看跌阈值', dataIndex: 'bearishThreshold', width: 95 },
  { title: '风险阈值', dataIndex: 'riskThreshold', width: 95 },
  { title: '版本', dataIndex: 'version', width: 80 },
  { title: '状态', dataIndex: 'status', width: 190 },
  { title: '操作', key: 'actions', width: 260 },
];

const groupOptions = computed(() =>
  groups.value.map((group) => ({
    label: `${group.groupName}（${group.groupCode} · ${group.status === 'active' ? '已启用' : '未启用'}）`,
    value: group.groupCode,
  })),
);

async function load() {
  loading.value = true;
  try {
    const [strategies, availableGroups] = await Promise.all([
      getRuleStrategies(),
      getRuleGroups(),
    ]);
    rows.value = strategies.rows;
    groups.value = availableGroups.rows;
  } catch (error) {
    message.error(error instanceof Error ? error.message : '应用方案加载失败');
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  Object.assign(form, {
    bearishThreshold: 60,
    bullishThreshold: 55,
    description: '',
    groups: [],
    riskThreshold: 80,
    strategyCode: '',
    strategyName: '',
  });
}

function openCreate() {
  editingCode.value = '';
  resetForm();
  editorOpen.value = true;
}

async function openEdit(record: Record<string, any>) {
  const row = record as RuleStrategy;
  try {
    const detail = await getRuleStrategy(row.strategyCode);
    editingCode.value = row.strategyCode;
    Object.assign(form, {
      bearishThreshold: detail.bearishThreshold,
      bullishThreshold: detail.bullishThreshold,
      description: detail.description ?? '',
      groups: detail.groups.map(({ groupCode, required, weight }) => ({
        groupCode,
        required,
        weight,
      })),
      riskThreshold: detail.riskThreshold,
      strategyCode: detail.strategyCode,
      strategyName: detail.strategyName,
    });
    editorOpen.value = true;
  } catch (error) {
    message.error(
      error instanceof Error ? error.message : '应用方案详情加载失败',
    );
  }
}

function addGroup() {
  form.groups.push({ groupCode: '', required: false, weight: 1 });
}

function validate() {
  if (!form.strategyCode.trim() || !form.strategyName.trim()) {
    return '请填写方案编码和名称';
  }
  if (form.groups.length === 0) return '请至少选择一个规则组';
  const codes = form.groups.map((item) => item.groupCode);
  if (codes.some((code) => !code)) return '请为每一行选择规则组';
  if (new Set(codes).size !== codes.length) return '同一规则组不能重复';
  if (
    form.groups.some(
      (item) => !item.weight || item.weight <= 0 || item.weight > 100,
    )
  ) {
    return '规则组权重须大于 0 且不超过 100';
  }
  if (
    [form.bullishThreshold, form.bearishThreshold, form.riskThreshold].some(
      (value) =>
        value === null || value === undefined || value <= 0 || value > 100,
    )
  ) {
    return '信号阈值须大于 0 且不超过 100';
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
    const payload: RuleStrategyUpsert = {
      bearishThreshold: form.bearishThreshold,
      bullishThreshold: form.bullishThreshold,
      description: form.description?.trim(),
      groups: form.groups.map(({ groupCode, required, weight }) => ({
        groupCode,
        required,
        weight,
      })),
      riskThreshold: form.riskThreshold,
      strategyCode: form.strategyCode.trim(),
      strategyName: form.strategyName.trim(),
    };
    await (editingCode.value
      ? updateRuleStrategy(editingCode.value, payload)
      : createRuleStrategy(payload));
    editorOpen.value = false;
    message.success('应用方案已保存');
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '应用方案保存失败');
  } finally {
    saving.value = false;
  }
}

function openCopy(record: Record<string, any>) {
  const row = record as RuleStrategy;
  copyingCode.value = row.strategyCode;
  copyForm.newCode = `${row.strategyCode}_COPY`;
  copyForm.newName = `${row.strategyName} 副本`;
  copyOpen.value = true;
}

async function openHistory(record: Record<string, any>) {
  const row = record as RuleStrategy;
  historyCode.value = row.strategyCode;
  try {
    historyRows.value = await getRuleStrategyVersions(row.strategyCode);
    historyOpen.value = true;
  } catch (error) {
    message.error(
      error instanceof Error ? error.message : '应用方案版本加载失败',
    );
  }
}

async function copy() {
  if (!copyForm.newCode.trim() || !copyForm.newName.trim()) {
    message.warning('请填写新方案编码和名称');
    return;
  }
  saving.value = true;
  try {
    await copyRuleStrategy(copyingCode.value, {
      newCode: copyForm.newCode.trim(),
      newName: copyForm.newName.trim(),
    });
    copyOpen.value = false;
    message.success('应用方案已复制为草稿');
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '应用方案复制失败');
  } finally {
    saving.value = false;
  }
}

async function toggle(record: Record<string, any>) {
  const row = record as RuleStrategy;
  try {
    await setRuleStrategyStatus(
      row.strategyCode,
      row.status === 'active' ? 'disabled' : 'active',
    );
    message.success(
      row.status === 'active' ? '应用方案已停用' : '应用方案已启用',
    );
    await load();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '状态修改失败');
  }
}

onMounted(load);
</script>

<template>
  <Card size="small">
    <Alert
      class="mb-3"
      show-icon
      type="info"
      message="可同时启用多个方案。各方案在自己的股票范围内独立生成信号，保留方案与版本，便于结合判断；评分未达到阈值时仍可能是观望。"
    />
    <div class="mb-3 flex items-center justify-between gap-3">
      <Typography.Text type="secondary"
        >选择多个规则组，配置权重、必选条件和信号阈值。</Typography.Text
      >
      <Button type="primary" @click="openCreate">新建应用方案</Button>
    </div>
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      row-key="strategyCode"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'groups'"
          >{{ record.groups?.length ?? 0 }} 组</template
        >
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
                ? '应用中'
                : record.status === 'disabled'
                  ? '已停用'
                  : '草稿'
            }}
          </Tag>
          <Tag v-if="record.usageMode === 'auxiliary'" color="blue">
            辅助决策
          </Tag>
          <Tag v-if="record.researchStatus === 'pending_final'" color="gold">
            待最终验证
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
              {{ record.status === 'active' ? '停用' : '启用应用' }}
            </Button>
          </Space>
        </template>
      </template>
    </Table>
  </Card>

  <Modal
    v-model:open="editorOpen"
    :confirm-loading="saving"
    :title="editingCode ? '编辑应用方案' : '新建应用方案'"
    width="800px"
    @ok="save"
  >
    <Form :model="form" layout="vertical">
      <Form.Item label="方案编码" required>
        <Input
          v-model:value="form.strategyCode"
          :disabled="!!editingCode"
          placeholder="S_TREND_VOLUME"
        />
      </Form.Item>
      <Form.Item label="方案名称" required
        ><Input v-model:value="form.strategyName" placeholder="趋势与量能确认"
      /></Form.Item>
      <Form.Item label="说明"
        ><Input.TextArea
          v-model:value="form.description"
          :rows="2"
          placeholder="说明组合目标、适用范围和风险边界"
      /></Form.Item>
      <Space wrap>
        <Form.Item label="看涨阈值"
          ><InputNumber
            v-model:value="form.bullishThreshold"
            :min="0.01"
            :max="100"
            :precision="2"
        /></Form.Item>
        <Form.Item label="看跌阈值"
          ><InputNumber
            v-model:value="form.bearishThreshold"
            :min="0.01"
            :max="100"
            :precision="2"
        /></Form.Item>
        <Form.Item label="风险阈值"
          ><InputNumber
            v-model:value="form.riskThreshold"
            :min="0.01"
            :max="100"
            :precision="2"
        /></Form.Item>
      </Space>
      <div class="mb-2 flex items-center justify-between">
        <Typography.Text strong>规则组搭配</Typography.Text>
        <Button size="small" @click="addGroup">添加规则组</Button>
      </div>
      <div
        v-for="(group, index) in form.groups"
        :key="index"
        class="mb-2 flex flex-wrap items-center gap-2"
      >
        <Select
          v-model:value="group.groupCode"
          :options="groupOptions"
          class="min-w-72 flex-1"
          show-search
          option-filter-prop="label"
          placeholder="选择规则组"
        />
        <InputNumber
          v-model:value="group.weight"
          :min="0.01"
          :max="100"
          :precision="2"
          addon-before="权重"
          class="w-36"
        />
        <span>必选</span>
        <Switch v-model:checked="group.required" />
        <Button danger size="small" @click="form.groups.splice(index, 1)"
          >移除</Button
        >
      </div>
      <Typography.Text type="secondary"
        >先保存为草稿；规则组启用后，再启用本应用方案。不同组引用同一规则时，执行时会去重。</Typography.Text
      >
    </Form>
  </Modal>

  <Modal
    v-model:open="copyOpen"
    :confirm-loading="saving"
    title="复制应用方案"
    @ok="copy"
  >
    <Form :model="copyForm" layout="vertical">
      <Form.Item label="新方案编码" required
        ><Input v-model:value="copyForm.newCode"
      /></Form.Item>
      <Form.Item label="新方案名称" required
        ><Input v-model:value="copyForm.newName"
      /></Form.Item>
    </Form>
  </Modal>

  <Modal
    v-model:open="historyOpen"
    :footer="null"
    :title="`应用方案版本 · ${historyCode}`"
    width="700px"
  >
    <div
      v-for="snapshot in historyRows"
      :key="snapshot.version"
      class="mb-3 rounded border p-3"
    >
      <Space wrap>
        <Tag color="blue">{{ snapshot.version }}</Tag>
        <Typography.Text strong>{{ snapshot.strategyName }}</Typography.Text>
        <Tag>{{ snapshot.status }}</Tag>
      </Space>
      <div class="mt-2">
        看涨 {{ snapshot.bullishThreshold }} / 看跌
        {{ snapshot.bearishThreshold }} / 风险 {{ snapshot.riskThreshold }}
      </div>
      <div class="mt-1">
        <Typography.Text type="secondary">
          规则组：{{
            snapshot.groups
              .map(
                (item) =>
                  `${item.groupCode} × ${item.weight}${item.required ? '（必选）' : ''}`,
              )
              .join('、')
          }}
        </Typography.Text>
      </div>
    </div>
  </Modal>
</template>
