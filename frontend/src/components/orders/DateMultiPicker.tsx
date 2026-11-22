import React, { useState } from 'react';

const WEEK = ['日', '一', '二', '三', '四', '五', '六'];

const ymd = (y: number, m: number, d: number) =>
  `${y}-${String(m + 1).padStart(2, '0')}-${String(d).padStart(2, '0')}`;

/**
 * 多選日期月曆（單日、連續、跳選可混用，PRD-005 §4.2-1）。`min` / `max` 為 `YYYY-MM-DD`（含），
 * 由呼叫端依後端 `serverTime` 與方案可預約區間計算，不以瀏覽器時間判斷。
 * 格線用 minmax(0, 1fr)、標題 nowrap、兩側鈕 flexShrink 0（SD-FRONTEND-SPEC §2.4）。
 */
const DateMultiPicker: React.FC<{
  selected: string[];
  onToggle: (date: string) => void;
  min: string;
  max: string;
  testIdPrefix: string;
}> = ({ selected, onToggle, min, max, testIdPrefix }) => {
  const [cursor, setCursor] = useState(() => {
    const [y, m] = (selected[0] ?? min).split('-').map(Number);
    return { y, m: m - 1 };
  });
  const first = new Date(Date.UTC(cursor.y, cursor.m, 1));
  const daysInMonth = new Date(Date.UTC(cursor.y, cursor.m + 1, 0)).getUTCDate();
  const lead = first.getUTCDay();
  const move = (delta: number) => {
    const d = new Date(Date.UTC(cursor.y, cursor.m + delta, 1));
    setCursor({ y: d.getUTCFullYear(), m: d.getUTCMonth() });
  };
  const navBtn: React.CSSProperties = {
    flexShrink: 0,
    border: 'none',
    background: 'var(--color-surface-low)',
    borderRadius: '9999px',
    width: '2rem',
    height: '2rem',
    cursor: 'pointer',
    color: 'var(--color-on-surface)'
  };
  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: '0.5rem'
        }}
      >
        <button type="button" style={navBtn} onClick={() => move(-1)} aria-label="上個月">
          ‹
        </button>
        <strong style={{ whiteSpace: 'nowrap' }}>
          {cursor.y} 年 {cursor.m + 1} 月
        </strong>
        <button type="button" style={navBtn} onClick={() => move(1)} aria-label="下個月">
          ›
        </button>
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
          gap: '0.25rem',
          textAlign: 'center',
          fontSize: '0.8125rem'
        }}
      >
        {WEEK.map((w) => (
          <span key={w} className="muted" style={{ fontSize: '0.75rem' }}>
            {w}
          </span>
        ))}
        {Array.from({ length: lead }, (_, i) => (
          <span key={`lead-${i}`} />
        ))}
        {Array.from({ length: daysInMonth }, (_, i) => {
          const date = ymd(cursor.y, cursor.m, i + 1);
          const disabled = date < min || date > max;
          const isSel = selected.includes(date);
          return (
            <button
              key={date}
              type="button"
              disabled={disabled}
              onClick={() => onToggle(date)}
              aria-pressed={isSel}
              data-testid={`${testIdPrefix}${date}`}
              style={{
                border: 'none',
                borderRadius: 'var(--radius-sm)',
                padding: '0.375rem 0',
                minWidth: 0,
                cursor: disabled ? 'not-allowed' : 'pointer',
                background: isSel ? 'var(--color-primary)' : 'var(--color-surface-low)',
                color: isSel
                  ? 'var(--color-on-primary)'
                  : disabled
                    ? 'var(--color-surface-highest)'
                    : 'var(--color-on-surface)',
                fontWeight: isSel ? 700 : 500
              }}
            >
              {i + 1}
            </button>
          );
        })}
      </div>
    </div>
  );
};

export default DateMultiPicker;
