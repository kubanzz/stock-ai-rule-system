import { describe, expect, it } from 'vitest';

import {
  normalizeStockApiError,
  unwrapAjaxResult,
  unwrapStockPageResult,
} from './ajax-result';

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

  it('normalizes rejected backend response bodies into readable errors', () => {
    const error = normalizeStockApiError({
      code: 400,
      msg: '成员规则尚未启用',
    });
    expect(error).toBeInstanceOf(Error);
    expect(error.message).toBe('成员规则尚未启用');
  });

  it('prefers the backend msg over an HTTP client error message', () => {
    const error = Object.assign(
      new Error('Request failed with status code 400'),
      {
        response: { data: { code: 400, msg: '规则组尚未启用' } },
      },
    );
    expect(normalizeStockApiError(error).message).toBe('规则组尚未启用');
  });

  it('preserves network errors and provides a fallback for unknown failures', () => {
    const error = new Error('Network Error');
    expect(normalizeStockApiError(error)).toBe(error);
    expect(normalizeStockApiError(undefined).message).toBe('股票业务请求失败');
  });
});
