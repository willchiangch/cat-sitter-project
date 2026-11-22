import { describe, expect, it } from 'vitest';
import { monthsBack } from './datetime';

// SD-ORDER §9.2：歷史月份篩選 = E3 / E4 `serverMonth` 起往前 12 個月（含當月）
describe('monthsBack', () => {
  it('自 serverMonth 起往前 12 個月，含當月、新到舊', () => {
    expect(monthsBack('2026-11')).toEqual([
      '2026-11',
      '2026-10',
      '2026-09',
      '2026-08',
      '2026-07',
      '2026-06',
      '2026-05',
      '2026-04',
      '2026-03',
      '2026-02',
      '2026-01',
      '2025-12'
    ]);
  });

  it('跨年', () => {
    const months = monthsBack('2027-01');
    expect(months).toHaveLength(12);
    expect(months[0]).toBe('2027-01');
    expect(months[1]).toBe('2026-12');
    expect(months[11]).toBe('2026-02');
  });

  it('自訂筆數', () => {
    expect(monthsBack('2026-03', 3)).toEqual(['2026-03', '2026-02', '2026-01']);
  });

  it.each([null, undefined, '', '2026-13', '2026-00', '2026-1', '2026-11-01', 'abc'])(
    '無值或格式不符 %s 回空陣列',
    (input) => {
      expect(monthsBack(input)).toEqual([]);
    }
  );
});
