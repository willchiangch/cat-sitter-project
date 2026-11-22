/**
 * 公開預約頁「未登入按送出」時暫存已選內容（PRD-005 EX-1 / AC-1.1 情境 2）：存 sessionStorage（只在本分頁、
 * 關閉即清），登入後回到預約頁還原。只存方案 / 日期 / 每日次數，不存毛孩與問卷（匿名看不到這兩區）。
 * 讀取時逐欄驗證形狀，不合格整份丟棄；sessionStorage 不可用（隱私模式、配額）時靜默略過。
 */
export interface BookingDraftGroup {
  planId: string;
  dates: string[];
  dailyCount: number;
}

const KEY_PREFIX = 'booking-draft:';
const MAX_GROUPS = 10;
const MAX_TOTAL_DATES = 60;
const YMD = /^\d{4}-\d{2}-\d{2}$/;

const keyOf = (sitterId: string) => `${KEY_PREFIX}${sitterId}`;

const isDraftGroup = (g: unknown): g is BookingDraftGroup => {
  if (!g || typeof g !== 'object') return false;
  const { planId, dates, dailyCount } = g as Record<string, unknown>;
  return (
    typeof planId === 'string' &&
    planId.length > 0 &&
    Array.isArray(dates) &&
    dates.every((d) => typeof d === 'string' && YMD.test(d)) &&
    typeof dailyCount === 'number' &&
    Number.isInteger(dailyCount) &&
    dailyCount >= 1 &&
    dailyCount <= 3
  );
};

/** 解析暫存字串；形狀不符或超過上限回 null（純函式，供測試） */
export const parseBookingDraft = (raw: string | null): BookingDraftGroup[] | null => {
  if (!raw) return null;
  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!Array.isArray(data) || data.length === 0 || data.length > MAX_GROUPS) return null;
  if (!data.every(isDraftGroup)) return null;
  const groups = data as BookingDraftGroup[];
  if (groups.reduce((n, g) => n + g.dates.length, 0) > MAX_TOTAL_DATES) return null;
  return groups.map((g) => ({ planId: g.planId, dates: [...g.dates], dailyCount: g.dailyCount }));
};

export const saveBookingDraft = (sitterId: string, groups: BookingDraftGroup[]): void => {
  try {
    sessionStorage.setItem(
      keyOf(sitterId),
      JSON.stringify(
        groups.map((g) => ({ planId: g.planId, dates: g.dates, dailyCount: g.dailyCount }))
      )
    );
  } catch {
    // 無法寫入時登入後就是空白預約頁，不阻擋導向登入
  }
};

export const loadBookingDraft = (sitterId: string): BookingDraftGroup[] | null => {
  try {
    return parseBookingDraft(sessionStorage.getItem(keyOf(sitterId)));
  } catch {
    return null;
  }
};

export const clearBookingDraft = (sitterId: string): void => {
  try {
    sessionStorage.removeItem(keyOf(sitterId));
  } catch {
    // ignore
  }
};
