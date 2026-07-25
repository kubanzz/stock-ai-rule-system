import type {
  RiskObjectListItem,
  RiskObjectQuery,
  RiskOverview,
  RiskSnapshot,
} from '#/api/stock/risk/types';

import { createApp, nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import RiskCenter from './index.vue';

const riskApi = vi.hoisted(() => ({
  getRiskObjectDetail: vi.fn(),
  getRiskObjects: vi.fn(),
  getRiskObjectTrend: vi.fn(),
  getRiskOverview: vi.fn(),
  getRiskSyncJob: vi.fn(),
  getRiskSyncStatus: vi.fn(),
  startRiskMarketSync: vi.fn(),
  startRiskStockSync: vi.fn(),
}));

vi.mock('#/api/stock/risk', () => ({
  ...riskApi,
  RISK_DECISION_SUPPORT_NOTICE: '仅用于测试的辅助决策提示',
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      setup(_, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

async function flushAsyncWork() {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve();
  }
  await nextTick();
}

function snapshot(
  object: RiskSnapshot['object'],
  level: RiskSnapshot['level'] = 'warning',
): RiskSnapshot {
  return {
    aScore: 48,
    cScore: 63,
    calculatedAt: '2026-07-18T18:10:00+08:00',
    completeness: 0.92,
    evidence: [],
    horizon: '1-5d',
    level,
    mScore: 1.05,
    modelVersion: 'risk-v1.0-shadow',
    object,
    riskConfidence: 0.86,
    sScore: 58,
    stage: 'repricing',
    tScore: 55,
    totalScore: 62.4,
    tradeDate: '2026-07-18',
    vScore: 72,
  };
}

const marketItem: RiskObjectListItem = {
  name: 'A 股全市场',
  object: { objectId: 'CN-A', objectType: 'market' },
  snapshot: snapshot({ objectId: 'CN-A', objectType: 'market' }),
};

const sectorItem: RiskObjectListItem = {
  name: '食品饮料',
  object: { objectId: 'SW1:801120', objectType: 'sector' },
  parentObject: { objectId: 'CN-A', objectType: 'market' },
  snapshot: snapshot({ objectId: 'SW1:801120', objectType: 'sector' }),
};

const stockItem: RiskObjectListItem = {
  name: '贵州茅台',
  object: { objectId: '600519.SH', objectType: 'stock' },
  parentObject: { objectId: 'SW1:801120', objectType: 'sector' },
  snapshot: snapshot(
    { objectId: '600519.SH', objectType: 'stock' },
    'critical',
  ),
};

describe('risk center interactions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    riskApi.getRiskOverview.mockResolvedValue({
      highRiskObjects: [stockItem],
      horizon: '1-5d',
      levelCounts: [],
      marketSnapshot: marketItem.snapshot,
      riskDisclaimer: '仅用于测试的辅助决策提示',
      tradeDate: '2026-07-18',
    } satisfies RiskOverview);
    riskApi.getRiskObjects.mockImplementation((query: RiskObjectQuery) => {
      const rows = {
        market: [marketItem],
        sector: [sectorItem],
        stock: [stockItem],
      }[query.objectType ?? 'stock'];
      return Promise.resolve({ rows, total: rows.length });
    });
    riskApi.getRiskObjectDetail.mockImplementation(
      (objectType: RiskObjectListItem['object']['objectType']) => {
        const item = {
          market: marketItem,
          sector: sectorItem,
          stock: stockItem,
        }[objectType];
        return Promise.resolve({
          ...item,
          activeTriggers: [],
          parentObjects: item.parentObject ? [item.parentObject] : [],
          snapshots: [item.snapshot],
        });
      },
    );
    riskApi.getRiskObjectTrend.mockResolvedValue([]);
    riskApi.getRiskSyncStatus.mockResolvedValue({
      activeJobs: [],
      latestMarketJob: null,
    });
  });

  it('reloads stocks with the clicked sector stable id', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    const app = createApp(RiskCenter);
    app.mount(container);
    await flushAsyncWork();
    riskApi.getRiskObjects.mockClear();

    const sectorButton =
      container.querySelector<HTMLButtonElement>('.sector-cell');
    expect(sectorButton).not.toBeNull();
    sectorButton?.click();
    await flushAsyncWork();

    expect(riskApi.getRiskObjects).toHaveBeenCalledWith(
      expect.objectContaining({
        objectType: 'stock',
        parentObjectId: 'SW1:801120',
        parentObjectType: 'sector',
      }),
    );
    app.unmount();
    container.remove();
  });

  it('clears the selected object when its detail request fails', async () => {
    const container = document.createElement('div');
    document.body.append(container);
    const app = createApp(RiskCenter);
    app.mount(container);
    await flushAsyncWork();
    expect(container.querySelector('.object-row.selected')).not.toBeNull();

    riskApi.getRiskObjectDetail.mockRejectedValueOnce(
      new Error('detail unavailable'),
    );
    container.querySelector<HTMLButtonElement>('.sector-cell')?.click();
    await flushAsyncWork();

    expect(container.querySelector('.object-row.selected')).toBeNull();
    app.unmount();
    container.remove();
  });

  it('starts market sync, polls the job and refreshes after completion', async () => {
    vi.useFakeTimers();
    const queuedJob = {
      createdAt: '2026-07-25T10:00:00+08:00',
      eventCount: 0,
      evidenceCount: 0,
      finishedAt: null,
      jobId: 'market-job',
      message: null,
      observationCount: 0,
      phase: 'syncing_market',
      progress: 20,
      scopeKey: 'market:CN-A',
      snapshotCount: 0,
      startedAt: '2026-07-25T10:00:01+08:00',
      status: 'running',
      tradeDate: '2026-07-24',
      unavailableDatasetCount: 0,
    } as const;
    riskApi.startRiskMarketSync.mockResolvedValue(queuedJob);
    riskApi.getRiskSyncJob.mockResolvedValue({
      ...queuedJob,
      finishedAt: '2026-07-25T10:01:00+08:00',
      message: '同步完成',
      phase: 'completed',
      progress: 100,
      status: 'succeeded',
    });
    const container = document.createElement('div');
    document.body.append(container);
    const app = createApp(RiskCenter);
    app.mount(container);
    await flushAsyncWork();
    riskApi.getRiskOverview.mockClear();

    const syncButton = [...container.querySelectorAll('button')].find(
      (button) => button.textContent?.includes('同步最新市场数据'),
    );
    syncButton?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await flushAsyncWork();
    expect(riskApi.startRiskMarketSync).toHaveBeenCalledOnce();

    await vi.advanceTimersByTimeAsync(1500);
    await flushAsyncWork();
    expect(riskApi.getRiskSyncJob).toHaveBeenCalledWith('market-job');
    expect(riskApi.getRiskOverview).toHaveBeenCalled();

    app.unmount();
    container.remove();
    vi.useRealTimers();
  });
});
