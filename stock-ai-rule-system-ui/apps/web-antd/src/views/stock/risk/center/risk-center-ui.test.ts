import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

const centerFile = (path: string) =>
  readFileSync(
    resolve(process.cwd(), 'apps/web-antd/src/views/stock/risk/center', path),
    'utf8',
  );

describe('risk center UI safety contract', () => {
  it('caps trends at the selected date and ignores superseded loads', () => {
    const page = centerFile('index.vue');

    expect(page).toContain('buildRiskTrendQuery(query)');
    expect(page).toContain('centerRequests.next()');
    expect(page).toContain('centerRequests.isCurrent(requestId)');
  });

  it('clears every selection result after the current center load fails', () => {
    const page = centerFile('index.vue');
    const catchBlock = page.slice(
      page.indexOf("'风险中心加载失败'"),
      page.indexOf("'风险中心加载失败'") + 500,
    );

    expect(catchBlock).toContain('resetSelection()');
    expect(page).toContain('selected.value = undefined');
    expect(page).toContain('detail.value = undefined');
    expect(page).toContain('trend.value = []');
  });

  it('loads market, sector and stock separately and scopes stocks by sector id', () => {
    const page = centerFile('index.vue');

    expect(page).toContain(
      'buildRiskObjectQueries(query, activeSectorId.value)',
    );
    expect(page).toContain('getRiskObjects(queries.market)');
    expect(page).toContain('getRiskObjects(queries.sector)');
    expect(page).toContain('getRiskObjects(queries.stock)');
    expect(page).toContain('async function selectRiskObject');
    expect(page).toContain('activeSectorId.value = item.object.objectId');
    expect(page).toContain('loadStockRows(item.object.objectId)');
    expect(page).not.toContain('parentName ===');
  });

  it.each([
    'risk-overview-panel.vue',
    'risk-sector-matrix.vue',
    'risk-object-drilldown.vue',
  ])(
    'suppresses formal score and level through riskDataState in %s',
    (file) => {
      const component = centerFile(file);

      expect(component).toContain('riskDataState');
      expect(component).toContain('RISK_DATA_STATE_LABELS');
      expect(component).toContain("=== 'ready'");
    },
  );

  it('uses explicit labels for all non-formal data states', () => {
    const state = centerFile('risk-center-state.ts');

    expect(state).toContain('数据不足');
    expect(state).toContain('数据过期');
    expect(state).toContain('数据不可用');
  });
});
