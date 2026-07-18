import { describe, expect, it } from 'vitest';

import {
  getGatePresentation,
  getRiskLevelPresentation,
  getRiskScoreTone,
} from './presentation';

describe('risk presentation', () => {
  it('uses stable labels and colors for all risk levels', () => {
    expect(getRiskLevelPresentation('normal')).toEqual({
      color: 'green',
      label: '正常',
    });
    expect(getRiskLevelPresentation('watch').label).toBe('关注');
    expect(getRiskLevelPresentation('warning').color).toBe('orange');
    expect(getRiskLevelPresentation('critical').color).toBe('red');
    expect(getRiskLevelPresentation(null)).toEqual({
      color: 'default',
      label: '数据不足',
    });
  });

  it('makes shadow gate state explicit', () => {
    expect(getGatePresentation('block', false)).toEqual({
      color: 'red',
      label: '建议拦截（影子）',
    });
    expect(getGatePresentation('downgrade', false).label).toContain('影子');
  });

  it('does not treat a missing score as low risk', () => {
    expect(getRiskScoreTone(null)).toBe('unavailable');
    expect(getRiskScoreTone(20)).toBe('low');
    expect(getRiskScoreTone(66)).toBe('high');
  });
});
