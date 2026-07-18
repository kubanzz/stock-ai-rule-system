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
