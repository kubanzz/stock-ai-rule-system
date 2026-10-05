export interface StockAjaxResult<T> {
  code: number;
  data?: T;
  msg?: string;
}

export interface StockPageResult<T> {
  code: number;
  msg?: string;
  rows: T[];
  total: number;
}

export interface StockPageData<T> {
  rows: T[];
  total: number;
}

function getErrorMessage(response: { code: number; msg?: string }) {
  return response.msg || `股票业务接口返回异常 code=${response.code}`;
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return typeof value === 'object' && value !== null
    ? (value as Record<string, unknown>)
    : undefined;
}

export function normalizeStockApiError(error: unknown): Error {
  const record = asRecord(error);
  const response = asRecord(record?.response);
  const candidates = [asRecord(response?.data), asRecord(record?.data), record];
  for (const candidate of candidates) {
    for (const key of ['msg', 'message']) {
      const message = candidate?.[key];
      if (typeof message === 'string' && message.trim()) {
        if (error instanceof Error && message === error.message) return error;
        return new Error(message);
      }
    }
  }
  return error instanceof Error ? error : new Error('股票业务请求失败');
}

export function unwrapAjaxResult<T>(response: StockAjaxResult<T>): T {
  if (response.code !== 200) {
    throw new Error(getErrorMessage(response));
  }
  return response.data as T;
}

export function unwrapStockPageResult<T>(
  response: StockPageResult<T>,
): StockPageData<T> {
  if (response.code !== 200) {
    throw new Error(getErrorMessage(response));
  }
  return {
    rows: response.rows,
    total: response.total,
  };
}
