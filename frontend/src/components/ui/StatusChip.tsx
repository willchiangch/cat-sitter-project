import React from 'react';
import { STATUS_TONE_STYLE, type StatusTone } from './statusTone';

interface StatusChipProps {
  label: string;
  tone?: StatusTone;
  status?: string;
  testId?: string;
}

/**
 * 子流程狀態（付款、憑證、變更、行程、照護日誌）的小徽章。文字由呼叫端經 statusLabels.ts 取得，
 * 顏色一律 STATUS_TONE_STYLE（SD-FRONTEND-SPEC §6.5）。訂單主狀態請用 StatusBadge。
 */
const StatusChip: React.FC<StatusChipProps> = ({ label, tone = 'neutral', status, testId }) => (
  <span
    data-testid={testId}
    data-status={status}
    style={{
      ...STATUS_TONE_STYLE[tone],
      display: 'inline-block',
      padding: '2px 8px',
      borderRadius: '4px',
      fontSize: '0.75rem',
      fontWeight: 600,
      whiteSpace: 'nowrap',
      flexShrink: 0
    }}
  >
    {label}
  </span>
);

export default StatusChip;
