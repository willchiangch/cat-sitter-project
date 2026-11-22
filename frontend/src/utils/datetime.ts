/**
 * 時間顯示一律 Asia/Taipei `YYYY-MM-DD HH:mm`（SD-ORDER §9.2、SD-FULFILL §9.1），無值顯示「—」。
 * 不以瀏覽器時區或語系決定格式。
 */
const TZ = 'Asia/Taipei';

const partsOf = (d: Date) => {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: TZ,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23'
  }).formatToParts(d);
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? '';
  return {
    y: get('year'),
    m: get('month'),
    d: get('day'),
    hh: get('hour'),
    mm: get('minute')
  };
};

export const EMPTY_TIME = '—';

export const formatDateTime = (iso: string | null | undefined): string => {
  if (!iso) return EMPTY_TIME;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return EMPTY_TIME;
  const p = partsOf(date);
  return `${p.y}-${p.m}-${p.d} ${p.hh}:${p.mm}`;
};

/** ISO 時間 → Asia/Taipei 日期 `YYYY-MM-DD` */
export const toTaipeiDate = (iso: string | Date): string => {
  const date = typeof iso === 'string' ? new Date(iso) : iso;
  const p = partsOf(date);
  return `${p.y}-${p.m}-${p.d}`;
};

/** ISO 時間 → Asia/Taipei 月份 `YYYY-MM` */
export const toTaipeiMonth = (iso: string | Date): string => toTaipeiDate(iso).slice(0, 7);

/**
 * 自 `fromMonth`（`YYYY-MM`）起往前 `count` 個月（含當月），新到舊。
 * 純月份運算，不讀瀏覽器時間；格式不符回空陣列。
 */
export const monthsBack = (fromMonth: string | null | undefined, count = 12): string[] => {
  const match = fromMonth ? /^(\d{4})-(0[1-9]|1[0-2])$/.exec(fromMonth) : null;
  if (!match) return [];
  const y = Number(match[1]);
  const m = Number(match[2]);
  return Array.from({ length: count }, (_, i) =>
    new Date(Date.UTC(y, m - 1 - i, 1)).toISOString().slice(0, 7)
  );
};

/** `YYYY-MM-DD` 加減天數（純日期運算，不受時區影響） */
export const addDays = (ymd: string, days: number): string => {
  const [y, m, d] = ymd.split('-').map(Number);
  const dt = new Date(Date.UTC(y, m - 1, d + days));
  return dt.toISOString().slice(0, 10);
};

export const isPast = (
  iso: string | null | undefined,
  nowIso: string | null | undefined
): boolean => !!iso && !!nowIso && new Date(nowIso).getTime() >= new Date(iso).getTime();
