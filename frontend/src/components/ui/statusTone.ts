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
