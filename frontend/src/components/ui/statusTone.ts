import type React from 'react';

/**
 * 狀態徽章 / 訊息的色調（fix-batch12 U7）。全部是實色底 + 深色字，對比度 ≥ 4.5:1（WCAG AA）：
 * info #1e40af/#dbeafe 7.2、success #065f46/#d1fae5 6.8、warning #92400e/#fef3c7 6.4、danger #991b1b/#fee2e2 6.8。
 * 過去 KYC 徽章沿用深色主題的 rgba(…, 0.2) 底 + 淺色字（例：審核中 #60a5fa，對比約 2.0），淺色主題下幾乎看不清。
 */
export type StatusTone = 'info' | 'success' | 'warning' | 'danger' | 'neutral';

export const STATUS_TONE_STYLE: Record<StatusTone, React.CSSProperties> = {
  info: { backgroundColor: '#dbeafe', color: '#1e40af' },
  success: { backgroundColor: '#d1fae5', color: '#065f46' },
  warning: { backgroundColor: '#fef3c7', color: '#92400e' },
  danger: { backgroundColor: '#fee2e2', color: '#991b1b' },
  neutral: {
    backgroundColor: 'var(--color-surface-high)',
    color: 'var(--color-on-surface-variant)'
  }
};

/** 成功訊息文字（白底或淺綠底皆 ≥ 5.2:1）。過去 #10b981 在白底只有 2.5:1 */
export const SUCCESS_TEXT_COLOR = '#047857';
export const SUCCESS_CONTAINER_COLOR = '#ecfdf5';

/**
 * 資訊橫幅（非按鈕的狀態說明，例如「您已取消此預約」「飼主已取消預約」）：白底、細框、左側 4px 色條、靠左、
 * 一般游標，搭配 role="status"。不要用 --color-surface-high 灰底膠囊——看起來像停用的按鈕
 * （fix-batch12 U3 飼主端、fix-batch13 K5 保母歷史訂單）。--color-on-surface 對白底約 13:1。
 */
export const INFO_BANNER_STYLE: React.CSSProperties = {
  textAlign: 'left',
  padding: '0.875rem 1rem',
  backgroundColor: 'var(--color-surface-lowest)',
  color: 'var(--color-on-surface)',
  border: '1px solid var(--color-surface-highest)',
  borderLeft: '4px solid var(--color-on-surface-variant)',
  borderRadius: 'var(--radius-sm)',
  fontSize: '0.875rem',
  fontWeight: '500',
  cursor: 'default'
};
