<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { MarketContext } from '#/api/stock';

import { computed, nextTick, ref, watch } from 'vue';

import { EchartsUI, useEcharts } from '@vben/plugins/echarts';

import { Empty, Progress, Tag } from 'ant-design-vue';

const props = defineProps<{
  context?: MarketContext;
}>();

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

const maxIndustryMagnitude = computed(() =>
  Math.max(
    1,
    ...(props.context?.industryStrength.map((item) =>
      Math.abs(item.strength),
    ) ?? []),
  ),
);

const sentimentPercent = computed(() => props.context?.sentiment.score ?? 0);

function renderTrend() {
  const trend = props.context?.trend ?? [];
  if (trend.length === 0) return;
  nextTick(() => {
    renderEcharts({
      animationDuration: 350,
      grid: { bottom: 24, left: 12, right: 12, top: 18, containLabel: true },
      series: [
        {
          areaStyle: { color: 'rgba(22, 119, 255, 0.08)' },
          data: trend.map((point) => point.value),
          lineStyle: { color: '#1677ff', width: 2 },
          name: props.context?.indexName ?? '基准指数',
          showSymbol: false,
          smooth: 0.25,
          type: 'line',
        },
      ],
      tooltip: { trigger: 'axis' },
      xAxis: {
        axisLabel: { color: '#8c8c8c', fontSize: 10, interval: 'auto' },
        axisLine: { lineStyle: { color: '#d9d9d9' } },
        boundaryGap: false,
        data: trend.map((point) => point.label),
        type: 'category',
      },
      yAxis: {
        axisLabel: { color: '#8c8c8c', fontSize: 10 },
        splitLine: { lineStyle: { color: 'rgba(140, 140, 140, 0.15)' } },
        scale: true,
        type: 'value',
      },
    });
  });
}

watch(() => props.context?.trend, renderTrend, { deep: true, immediate: true });
</script>

<template>
  <aside class="market-panel">
    <div class="panel-heading">
      <div>
        <h3>市场环境概览</h3>
        <p>与当前市场及筛选口径同步</p>
      </div>
      <Tag :color="context?.available ? 'green' : 'default'">
        {{ context?.available ? context.status : '暂无基准行情' }}
      </Tag>
    </div>

    <section class="context-section index-section">
      <div class="section-title">
        <span>大盘走势</span><small>近 20 个交易日</small>
      </div>
      <div v-if="context?.available" class="index-summary">
        <strong>{{ context.indexName }}</strong>
        <span>{{ context.indexValue ?? '--' }}</span>
        <em :class="(context.changePct ?? 0) >= 0 ? 'positive' : 'negative'">
          {{ (context.changePct ?? 0) >= 0 ? '+' : ''
          }}{{ context.changePct ?? '--' }}%
        </em>
      </div>
      <EchartsUI
        v-if="context?.trend.length"
        ref="chartRef"
        class="trend-chart"
      />
      <Empty
        v-else
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无基准行情"
      />
    </section>

    <section class="context-section">
      <div class="section-title">
        <span>行业强弱</span><small>平均涨跌幅排名</small>
      </div>
      <div v-if="context?.industryStrength.length" class="industry-list">
        <div
          v-for="item in context.industryStrength"
          :key="item.industry"
          class="industry-row"
        >
          <span>{{ item.industry }}</span>
          <div class="industry-track">
            <i
              :class="item.strength >= 0 ? 'bar-positive' : 'bar-negative'"
              :style="{
                width: `${(Math.abs(item.strength) / maxIndustryMagnitude) * 100}%`,
              }"
            ></i>
          </div>
          <strong :class="item.strength >= 0 ? 'positive' : 'negative'">
            {{ item.strength >= 0 ? '+' : '' }}{{ item.strength }}%
          </strong>
        </div>
      </div>
      <Empty
        v-else
        :image="Empty.PRESENTED_IMAGE_SIMPLE"
        description="暂无行业行情"
      />
    </section>

    <section class="context-section">
      <div class="section-title">
        <span>信号情绪（7 日）</span><small>规则信号分布</small>
      </div>
      <div class="sentiment-row">
        <Progress
          :percent="sentimentPercent"
          :status="
            context?.sentiment.status === 'bearish' ? 'exception' : 'normal'
          "
          type="dashboard"
          :width="82"
        />
        <div>
          <strong>{{ context?.sentiment.label ?? '暂无数据' }}</strong>
          <p>仅反映规则信号结构，不代表市场确定性方向。</p>
        </div>
      </div>
    </section>

    <section class="context-section risk-section">
      <div class="section-title">
        <span>风险概览</span>
        <small>{{ context?.riskOverview.syncStatus }}</small>
      </div>
      <div class="risk-stats">
        <div>
          <span>高风险信号</span>
          <strong>{{ context?.riskOverview.highRiskCount ?? 0 }}</strong>
        </div>
        <div>
          <span>占比</span>
          <strong>{{ context?.riskOverview.highRiskRatio ?? '--' }}%</strong>
        </div>
      </div>
      <p>{{ context?.riskOverview.summary ?? '暂无市场风险环境数据' }}</p>
    </section>
  </aside>
</template>

<style scoped>
.market-panel {
  overflow: hidden;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 6px;
}

.panel-heading,
.section-title,
.index-summary,
.industry-row,
.sentiment-row,
.risk-stats {
  display: flex;
  align-items: center;
}

.panel-heading {
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid hsl(var(--border));
}

.panel-heading h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
}

.panel-heading p,
.context-section p {
  margin: 3px 0 0;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.context-section {
  padding: 14px 16px;
  border-bottom: 1px solid hsl(var(--border));
}

.context-section:last-child {
  border-bottom: 0;
}

.section-title {
  justify-content: space-between;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 600;
}

.section-title small {
  font-size: 10px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
}

.index-summary {
  gap: 8px;
}

.index-summary strong {
  margin-right: auto;
  font-size: 12px;
}

.index-summary span {
  font-size: 19px;
  font-weight: 650;
}

.index-summary em {
  font-size: 12px;
  font-style: normal;
}

.trend-chart {
  height: 176px !important;
  margin-top: 4px;
}

.industry-list {
  display: grid;
  gap: 9px;
}

.industry-row {
  display: grid;
  grid-template-columns: 64px minmax(60px, 1fr) 58px;
  gap: 8px;
  font-size: 11px;
}

.industry-row strong {
  text-align: right;
}

.industry-track {
  height: 6px;
  overflow: hidden;
  background: hsl(var(--muted));
  border-radius: 3px;
}

.industry-track i {
  display: block;
  height: 100%;
  border-radius: 3px;
}

.bar-positive {
  background: #16a36a;
}

.bar-negative {
  background: #e5484d;
}

.sentiment-row {
  gap: 14px;
  align-items: center;
}

.sentiment-row strong {
  font-size: 12px;
}

.risk-stats {
  gap: 24px;
  margin-bottom: 8px;
}

.risk-stats div {
  display: grid;
  gap: 2px;
}

.risk-stats span {
  font-size: 10px;
  color: hsl(var(--muted-foreground));
}

.risk-stats strong {
  font-size: 18px;
}

.positive {
  color: #16a36a;
}

.negative {
  color: #e5484d;
}
</style>
