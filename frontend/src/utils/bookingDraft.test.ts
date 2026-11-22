import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  clearBookingDraft,
  loadBookingDraft,
  parseBookingDraft,
  saveBookingDraft
} from './bookingDraft';

// PRD-005 EX-1 / AC-1.1 情境 2：匿名送出前暫存、登入後還原
const group = { planId: 'p1', dates: ['2026-12-01', '2026-12-02'], dailyCount: 2 };

afterEach(() => sessionStorage.clear());

describe('bookingDraft', () => {
  it('存取往返；只存方案 / 日期 / 每日次數', () => {
    saveBookingDraft('s1', [{ ...group, petIds: ['x'] } as typeof group]);
    expect(JSON.parse(sessionStorage.getItem('booking-draft:s1')!)).toEqual([group]);
    expect(loadBookingDraft('s1')).toEqual([group]);
    expect(loadBookingDraft('other')).toBeNull();
  });

  it('clear 後讀不到', () => {
    saveBookingDraft('s1', [group]);
    clearBookingDraft('s1');
    expect(loadBookingDraft('s1')).toBeNull();
  });

  it.each([
    ['非 JSON', '{oops'],
    ['不是陣列', '{"planId":"p1"}'],
    ['空陣列', '[]'],
    ['planId 空字串', JSON.stringify([{ ...group, planId: '' }])],
    ['日期格式錯', JSON.stringify([{ ...group, dates: ['12/01'] }])],
    ['每日次數 0', JSON.stringify([{ ...group, dailyCount: 0 }])],
    ['每日次數 4', JSON.stringify([{ ...group, dailyCount: 4 }])],
    ['每日次數非整數', JSON.stringify([{ ...group, dailyCount: 1.5 }])],
    ['群組 > 10', JSON.stringify(Array.from({ length: 11 }, () => group))],
    [
      '日期總數 > 60',
      JSON.stringify([
        {
          ...group,
          dates: Array.from(
            { length: 61 },
            (_, i) =>
              `2026-${String(1 + Math.floor(i / 28)).padStart(2, '0')}-${String(1 + (i % 28)).padStart(2, '0')}`
          )
        }
      ])
    ]
  ])('形狀不合格整份丟棄：%s', (_label, raw) => {
    expect(parseBookingDraft(raw)).toBeNull();
  });

  it('sessionStorage 不可用時不拋錯', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(() => saveBookingDraft('s1', [group])).not.toThrow();
    expect(loadBookingDraft('s1')).toBeNull();
  });
});
