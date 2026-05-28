import React from 'react';

type OrderStatus = 'PENDING' | 'PENDING_PAYMENT' | 'PAID' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED';

interface StatusBadgeProps {
  status: OrderStatus | string;
}

const StatusBadge: React.FC<StatusBadgeProps> = ({ status }) => {
  const getStatusConfig = (s: string) => {
    switch (s) {
      case 'PENDING':
        return { label: '待評估', bg: '#ffbf00', text: '#563e00' }; // primary_container
      case 'PENDING_PAYMENT':
        return { label: '待付款', bg: '#f95630', text: '#520c00' }; // error_container
      case 'PAID':
      case 'CONFIRMED':
        return { label: s === 'PAID' ? '已付款' : '已確認', bg: '#91f78e', text: '#005e17' }; // tertiary_container
      case 'COMPLETED':
        return {
          label: '已結案',
          bg: 'var(--color-surface-high)',
          text: 'var(--color-on-surface-variant)'
        };
      default:
        return {
          label: s,
          bg: 'var(--color-surface-low)',
          text: 'var(--color-on-surface-variant)'
        };
    }
  };

  const config = getStatusConfig(status);

  return (
    <span
      style={{
        display: 'inline-block',
        padding: '2px 8px',
        borderRadius: '4px',
        fontSize: '12px',
        fontWeight: 'bold',
        backgroundColor: config.bg,
        color: config.text
      }}
    >
      {config.label}
    </span>
  );
};

export default StatusBadge;
