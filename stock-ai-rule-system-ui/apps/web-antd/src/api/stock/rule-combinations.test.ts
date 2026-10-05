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
  it('allows independently enabling and disabling multiple strategies in mock mode', async () => {
    vi.stubEnv('VITE_STOCK_USE_MOCK', 'true');
    const api = await import('./rule-combinations');
    for (const strategyCode of ['S_FIRST', 'S_SECOND']) {
      await api.createRuleStrategy({
        bearishThreshold: 60,
        bullishThreshold: 60,
        groups: [{ groupCode: 'G_TEST', required: true, weight: 1 }],
        riskThreshold: 80,
        strategyCode,
        strategyName: strategyCode,
      });
      await api.setRuleStrategyStatus(strategyCode, 'active');
    }
    expect((await api.getRuleStrategies()).rows.map((row) => row.status)).toEqual(['active', 'active']);

    await api.setRuleStrategyStatus('S_SECOND', 'disabled');
    expect((await api.getRuleStrategy('S_FIRST')).status).toBe('active');
    expect((await api.getRuleStrategy('S_SECOND')).status).toBe('disabled');
  });

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

  it('shows the backend dependency rejection when activating a group', async () => {
    const reason =
      '启用规则组前，成员规则必须已启用：R_P4_VOTE_001_CHANGE_PCT_5D';
    requestMocks.put.mockRejectedValue({ code: 400, msg: reason });
    const api = await import('./rule-combinations');

    await expect(
      api.setRuleGroupStatus('P4-VOTE-001', 'active'),
    ).rejects.toThrow(reason);
    expect(requestMocks.put).toHaveBeenCalledWith(
      '/rule-groups/P4-VOTE-001/status',
      {
        status: 'active',
      },
    );
  });

  it('shows the backend rejection from a nested HTTP response when activating a strategy', async () => {
    const reason = '启用应用方案前，规则组必须已启用：P4-VOTE-001';
    requestMocks.put.mockRejectedValue({
      response: { data: { code: 400, msg: reason } },
    });
    const api = await import('./rule-combinations');

    await expect(
      api.setRuleStrategyStatus('P4-VOTE-001', 'active'),
    ).rejects.toThrow(reason);
  });

  it('keeps business error messages returned with HTTP success when saving a group', async () => {
    requestMocks.post.mockResolvedValue({
      data: { code: 400, msg: '规则组编码已存在' },
    });
    const api = await import('./rule-combinations');

    await expect(
      api.createRuleGroup({
        aggregation: 'WEIGHTED',
        groupCode: 'P4-VOTE-001',
        groupName: 'P4 规则组',
        members: [{ required: false, ruleCode: 'R_P4', weight: 1 }],
        minMatchedRules: 1,
      }),
    ).rejects.toThrow('规则组编码已存在');
  });

  it('normalizes list and detail HTTP failures and preserves network errors', async () => {
    requestMocks.get.mockRejectedValueOnce({
      code: 400,
      msg: '规则组查询失败',
    });
    requestMocks.get.mockRejectedValueOnce({
      response: { data: { code: 404, msg: '找不到应用方案' } },
    });
    const api = await import('./rule-combinations');

    await expect(api.getRuleGroups()).rejects.toThrow('规则组查询失败');
    await expect(api.getRuleStrategy('P4-VOTE-001')).rejects.toThrow(
      '找不到应用方案',
    );
    const networkError = new Error('Network Error');
    requestMocks.put.mockRejectedValue(networkError);
    await expect(api.setRuleGroupStatus('P4-VOTE-001', 'active')).rejects.toBe(
      networkError,
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
