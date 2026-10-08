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
  Alert: {
    props: ['message', 'description'],
    template: '<div>{{ message }} {{ description }}</div>',
  },
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
    emits: ['filters', 'backfill', 'refresh'],
    props: ['query', 'loading'],
    template: `<div>
      <span data-pool>{{ query.poolCode }}</span>
      <button data-select @click="$emit('filters', { poolCode: 'research-sz125', date: '2026-09-30' })">选择分组</button>
      <button data-strategy @click="$emit('filters', { strategyCode: 'P4-VOTE-001' })">选择方案</button>
      <button data-backfill @click="$emit('backfill')">同步</button>
      <button data-refresh @click="$emit('refresh')">刷新</button>
      <span data-loading>{{ loading }}</span>
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
  it('keeps the newest filter result when an older request finishes later', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    app = createApp(SignalDashboard);
    app.mount(container);
    await flush();

    let resolveOlder!: (value: unknown) => void;
    api.getSignalDashboard
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveOlder = resolve;
          }),
      )
      .mockResolvedValueOnce({
        tradeDate: '2026-09-30',
        metrics: [],
        signals: [],
      });
    container.querySelector<HTMLButtonElement>('[data-select]')!.click();
    container.querySelector<HTMLButtonElement>('[data-strategy]')!.click();
    await flush();
    expect(container.querySelector('[data-loading]')?.textContent).toBe(
      'false',
    );
    expect(container.textContent).toContain('基准行情交易日：2026-09-30');

    resolveOlder({ tradeDate: '2026-09-28', metrics: [], signals: [] });
    await flush();
    expect(container.textContent).toContain('基准行情交易日：2026-09-30');
    expect(container.textContent).not.toContain('基准行情交易日：2026-09-28');
  });

  it('ignores a stale timeout while the newest request is still loading', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    app = createApp(SignalDashboard);
    app.mount(container);
    await flush();

    let rejectOlder!: (reason: unknown) => void;
    let resolveNewest!: (value: unknown) => void;
    api.getSignalDashboard
      .mockImplementationOnce(
        () =>
          new Promise((_resolve, reject) => {
            rejectOlder = reject;
          }),
      )
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            resolveNewest = resolve;
          }),
      );
    container.querySelector<HTMLButtonElement>('[data-select]')!.click();
    container.querySelector<HTMLButtonElement>('[data-strategy]')!.click();
    rejectOlder(new Error('timeout of 10000ms exceeded'));
    await flush();
    expect(container.querySelector('[data-loading]')?.textContent).toBe('true');
    expect(container.textContent).not.toContain('看板读取失败');

    resolveNewest({ tradeDate: '2026-09-30', metrics: [], signals: [] });
    await flush();
    expect(container.querySelector('[data-loading]')?.textContent).toBe(
      'false',
    );
    expect(container.textContent).toContain('读取成功');
  });

  it('fetches the fallback pool before displaying its dashboard', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    app = createApp(SignalDashboard);
    app.mount(container);
    await flush();

    container.querySelector<HTMLButtonElement>('[data-select]')!.click();
    await flush();
    api.getWatchlists.mockResolvedValue([
      { poolId: 'my-follow', poolName: '我的关注', stocks: [], total: 1 },
    ]);
    api.getSignalDashboard
      .mockResolvedValueOnce({
        tradeDate: '2026-09-28',
        metrics: [],
        signals: [],
      })
      .mockResolvedValueOnce({
        tradeDate: '2026-09-30',
        metrics: [],
        signals: [],
      });
    container.querySelector<HTMLButtonElement>('[data-refresh]')!.click();
    await flush();

    expect(container.querySelector('[data-pool]')?.textContent).toBe(
      'my-follow',
    );
    expect(api.getSignalDashboard).toHaveBeenLastCalledWith(
      expect.objectContaining({ poolCode: 'my-follow' }),
    );
    expect(container.textContent).toContain('基准行情交易日：2026-09-30');
  });

  it('preserves the previous data and exposes the current API error', async () => {
    api.getSignalDashboard.mockResolvedValue({
      tradeDate: '2026-09-30',
      metrics: [],
      signals: [],
    });
    const container = document.createElement('div');
    document.body.append(container);
    app = createApp(SignalDashboard);
    app.mount(container);
    await flush();

    api.getSignalDashboard.mockRejectedValueOnce({ msg: '股票池查询失败' });
    container.querySelector<HTMLButtonElement>('[data-select]')!.click();
    await flush();
    expect(container.textContent).toContain('看板读取失败：股票池查询失败');
    expect(container.textContent).toContain('当前显示的可能是上次读取的数据');
    expect(container.textContent).toContain('基准行情交易日：2026-09-30');
    expect(container.querySelector('[data-loading]')?.textContent).toBe(
      'false',
    );
  });

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
