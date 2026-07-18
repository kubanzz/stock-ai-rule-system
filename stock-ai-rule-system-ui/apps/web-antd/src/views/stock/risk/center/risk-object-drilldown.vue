<script lang="ts" setup>
import type { RiskHierarchy } from './risk-center-state';

import type { RiskObjectListItem } from '#/api/stock/risk/types';

import { Card, Empty, Tag } from 'ant-design-vue';

import { RiskLevelTag, RiskScoreDisplay } from '../shared';
import { RISK_DATA_STATE_LABELS, riskDataState } from './risk-center-state';

defineProps<{
  hierarchy: RiskHierarchy;
  loading: boolean;
  selectedId?: string;
}>();

const emit = defineEmits<{
  select: [item: RiskObjectListItem];
}>();

const columns: Array<{
  key: keyof RiskHierarchy;
  title: string;
}> = [
  { key: 'market', title: '市场' },
  { key: 'sectors', title: '行业' },
  { key: 'stocks', title: '个股' },
];
</script>

<template>
  <Card :loading="loading" size="small" title="三级风险下钻">
    <div class="hierarchy-grid">
      <section v-for="column in columns" :key="column.key">
        <h4>{{ column.title }}</h4>
        <div v-if="hierarchy[column.key].length" class="object-list">
          <button
            v-for="item in hierarchy[column.key]"
            :key="item.object.objectId"
            :class="{ selected: selectedId === item.object.objectId }"
            class="object-row"
            type="button"
            @click="emit('select', item)"
          >
            <span>
              <strong>{{ item.name }}</strong>
              <small>{{ item.object.objectId }}</small>
            </span>
            <template v-if="riskDataState(item.snapshot) === 'ready'">
              <RiskScoreDisplay
                :completeness="item.snapshot.completeness"
                :score="item.snapshot.totalScore"
              />
              <RiskLevelTag :level="item.snapshot.level" />
            </template>
            <Tag v-else color="default">
              {{ RISK_DATA_STATE_LABELS[riskDataState(item.snapshot)] }}
            </Tag>
          </button>
        </div>
        <Empty v-else :description="`暂无${column.title}数据`" />
      </section>
    </div>
  </Card>
</template>

<style scoped>
.hierarchy-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.hierarchy-grid h4 {
  margin: 0 0 8px;
  font-weight: 600;
}

.object-list {
  display: grid;
  gap: 6px;
  max-height: 340px;
  overflow: auto;
}

.object-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 8px;
  align-items: center;
  padding: 9px;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.object-row.selected,
.object-row:hover {
  background: hsl(var(--accent) / 45%);
  border-color: hsl(var(--primary) / 55%);
}

.object-row strong,
.object-row small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.object-row small {
  margin-top: 2px;
  color: hsl(var(--muted-foreground));
}

@media (max-width: 960px) {
  .hierarchy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
