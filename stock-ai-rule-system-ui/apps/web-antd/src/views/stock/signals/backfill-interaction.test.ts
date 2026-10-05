import { createApp, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import SignalDashboard from './index.vue';

const api = vi.hoisted(() => ({
  getLatestSignalBackfillRun: vi.fn(),
  getSignalBackfillRun: vi.fn(),
  getSignalDashboard: vi.fn(),
  getRuleStrategies: vi.fn(),
  getWatchlists: vi.fn(),
  startSignalBackfill: vi.fn(),
}));
const router = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock('#/api/stock', () => api);
vi.mock('#/api/stock/rule-combinations', () => api);
vi.mock('vue-router', () => ({ useRouter: () => router }));
vi.mock('@vben/common-ui', () => ({
  Page: { template: '<main><slot /></main>' },
}));
vi.mock('ant-design-vue', () => ({
  Alert: { props: ['message'], template: '<div>{{ message }}</div>' },
  Progress: { template: '<div />' },
  message: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
}));
vi.mock('../components/risk-alert.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('./components/market-context-panel.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('./components/signal-metric-grid.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('./components/signal-table.vue', () => ({
  default: {
    emits: ['detail'],
    template: `<button data-detail @click="$emit('detail', { symbol: '600519.SH', signalDate: '2026-09-28', signalId: 812, strategyCode: 'P4-VOTE-001', strategyVersion: 'v1' })">详情</button>`,
  },
}));
vi.mock('./components/stock-picker-drawer.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('./components/watchlist-manager-drawer.vue', () => ({
  default: { template: '<div />' },
}));
vi.mock('./components/signal-dashboard-toolbar.vue', () => ({
  default: {
    emits: ['filters', 'backfill'],
    props: ['query'],
    template: `<div>
      <span data-pool>{{ query.poolCode }}</span>
      <button data-select @click="$emit('filters', { poolCode: 'research-sz125', date: '2026-09-30' })">选择分组</button>
      <button data-strategy @click="$emit('filters', { strategyCode: 'P4-VOTE-001' })">选择方案</button>
      <button data-backfill @click="$emit('backfill')">同步</button>
    </div>`,
  },
}));

let app: ReturnType<typeof createApp> | undefined;

async function flush() {
  for (let index = 0; index < 8; index += 1) await Promise.resolve();
  await nextTick();
}

beforeEach(() => {
  vi.clearAllMocks();
  api.getLatestSignalBackfillRun.mockResolvedValue(null);
  api.getSignalDashboard.mockResolvedValue({ metrics: [], signals: [] });
  api.getRuleStrategies.mockResolvedValue({ rows: [], total: 0 });
  api.getWatchlists.mockResolvedValue([
    { poolId: 'my-follow', poolName: '我的关注', stocks: [], total: 1 },
    { poolId: 'research-sz125', poolName: '研究分组', stocks: [], total: 125 },
  ]);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  document.body.replaceChildren();
});

describe('backfill completion in the signal dashboard', () => {
  it('filters by strategy and opens the exact historical signal identity', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    app = createApp(SignalDashboard);
    app.mount(container);
    await flush();

    container.querySelector<HTMLButtonElement>('[data-strategy]')!.click();
    await flush();
    expect(api.getSignalDashboard).toHaveBeenLastCalledWith(expect.objectContaining({
      strategyCode: 'P4-VOTE-001',
      pageNum: 1,
    }));

    container.querySelector<HTMLButtonElement>('[data-detail]')!.click();
    expect(router.push).toHaveBeenCalledWith({
      name: 'StockDetail',
      params: { symbol: '600519.SH' },
      query: {
        date: '2026-09-28',
        signalId: '812',
        strategyCode: 'P4-VOTE-001',
        strategyVersion: 'v1',
      },
    });
  });
});
