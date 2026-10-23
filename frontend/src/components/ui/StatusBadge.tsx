import React from 'react';
import { orderStatusLabel } from './statusLabels';

interface StatusBadgeProps {
  status: string;
}

// 顏色依語意分組；文字一律來自 statusLabels.ts（過去 default 分支直接顯示英文狀態碼，例如 IN_PROGRESS）
const getStatusColors = (s: string): { bg: string; text: string } => {
  switch (s) {
    case 'PENDING':
      return { bg: '#ffbf00', text: '#563e00' }; // primary_container
    case 'PENDING_PAYMENT':
    case 'REFUND_VERIFY':
    case 'DISPUTED':
      return { bg: '#f95630', text: '#520c00' }; // error_container：需要使用者處理
    case 'PAID':
    case 'CONFIRMED':
    case 'IN_PROGRESS':
      return { bg: '#91f78e', text: '#005e17' }; // tertiary_container
    case 'MODIFYING':
      return { bg: '#ffdea6', text: '#563e00' };
    case 'COMPLETED':
    case 'CANCELLED':
      return { bg: 'var(--color-surface-high)', text: 'var(--color-on-surface-variant)' };
    default:
      return { bg: 'var(--color-surface-low)', text: 'var(--color-on-surface-variant)' };
  }
};

const StatusBadge: React.FC<StatusBadgeProps> = ({ status }) => {
  const colors = getStatusColors(status);

  return (
    <span
      data-testid="order-status-badge"
      data-status={status}
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        borderRadius: '4px',
        fontSize: '12px',
        fontWeight: 'bold',
        backgroundColor: colors.bg,
        color: colors.text
      }}
    >
      {orderStatusLabel(status)}
    </span>
  );
};

export default StatusBadge;
