import { describe, expect, it } from 'vitest';

import { unwrapAjaxResult, unwrapStockPageResult } from './ajax-result';

describe('stock ajax result helpers', () => {
  it('unwraps backend AjaxResult when code is 200', () => {
    const result = unwrapAjaxResult({
      code: 200,
      data: { signal: 'bullish', symbol: 'AAPL' },
      msg: 'ok',
    });

    expect(result).toEqual({ signal: 'bullish', symbol: 'AAPL' });
  });

  it('unwraps backend PageResult rows and total when code is 200', () => {
    const result = unwrapStockPageResult({
      code: 200,
      msg: 'ok',
      rows: [{ signal: 'watch', symbol: 'MSFT' }],
      total: 1,
    });

    expect(result).toEqual({
      rows: [{ signal: 'watch', symbol: 'MSFT' }],
      total: 1,
    });
  });

  it('throws a readable error when backend code is not 200', () => {
    expect(() =>
      unwrapAjaxResult({
        code: 500,
        msg: '规则服务暂不可用',
      }),
    ).toThrow('规则服务暂不可用');
  });
});
