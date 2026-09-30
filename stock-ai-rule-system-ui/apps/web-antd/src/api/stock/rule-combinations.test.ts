import { beforeEach, describe, expect, it, vi } from 'vitest';

const requestMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ baseRequestClient: requestMocks }));

beforeEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
  vi.resetModules();
});

describe('rule combination API', () => {
  it('uses the backend for listing, saving, and applying a strategy in real mode', async () => {
    requestMocks.get.mockResolvedValue({
      data: { code: 200, rows: [], total: 0 },
    });
    requestMocks.post.mockResolvedValue({
      data: { code: 200, data: { strategyCode: 'S_TEST', status: 'draft' } },
    });
    requestMocks.put.mockResolvedValue({
      data: { code: 200, data: { strategyCode: 'S_TEST', status: 'active' } },
    });
    const api = await import('./rule-combinations');

    await expect(api.getRuleStrategies()).resolves.toEqual({
      rows: [],
      total: 0,
    });
    await api.createRuleStrategy({
      bearishThreshold: 60,
      bullishThreshold: 55,
      groups: [{ groupCode: 'G_TEST', required: true, weight: 1 }],
      riskThreshold: 80,
      strategyCode: 'S_TEST',
      strategyName: '测试方案',
    });
    await api.setRuleStrategyStatus('S_TEST', 'active');

    expect(requestMocks.get).toHaveBeenCalledWith('/rule-strategies');
    expect(requestMocks.post).toHaveBeenCalledWith(
      '/rule-strategies',
      expect.objectContaining({
        groups: [{ groupCode: 'G_TEST', required: true, weight: 1 }],
      }),
    );
    expect(requestMocks.put).toHaveBeenCalledWith(
      '/rule-strategies/S_TEST/status',
      { status: 'active' },
    );
  });

  it('keeps copied groups and strategies as separate drafts in mock mode', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');
    const api = await import('./rule-combinations');

    await api.createRuleGroup({
      aggregation: 'WEIGHTED',
      groupCode: 'G_SOURCE',
      groupName: '原始组',
      members: [{ required: true, ruleCode: 'R_1', weight: 2 }],
      minMatchedRules: 1,
    });
    await api.setRuleGroupStatus('G_SOURCE', 'active');
    const copiedGroup = await api.copyRuleGroup('G_SOURCE', {
      newCode: 'G_COPY',
      newName: '复制组',
    });
    expect(copiedGroup).toMatchObject({
      groupCode: 'G_COPY',
      members: [{ ruleCode: 'R_1', weight: 2 }],
      status: 'draft',
    });

    await api.createRuleStrategy({
      bearishThreshold: 60,
      bullishThreshold: 55,
      groups: [{ groupCode: 'G_SOURCE', required: false, weight: 1 }],
      riskThreshold: 80,
      strategyCode: 'S_SOURCE',
      strategyName: '原始方案',
    });
    await api.setRuleStrategyStatus('S_SOURCE', 'active');
    const copiedStrategy = await api.copyRuleStrategy('S_SOURCE', {
      newCode: 'S_COPY',
      newName: '复制方案',
    });
    expect(copiedStrategy).toMatchObject({
      groups: [{ groupCode: 'G_SOURCE' }],
      status: 'draft',
      strategyCode: 'S_COPY',
    });
    const groups = await api.getRuleGroups();
    const strategies = await api.getRuleStrategies();
    expect(groups.total).toBe(2);
    expect(strategies.total).toBe(2);
    expect(requestMocks.post).not.toHaveBeenCalled();
  });
});
