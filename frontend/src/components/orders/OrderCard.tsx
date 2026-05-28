import React from 'react';
import Card from '../ui/Card';
import StatusBadge from '../ui/StatusBadge';

interface OrderCardProps {
  order: {
    id: string;
    ownerName: string;
    status: string;
    totalAmount: number;
    scheduledDates: string;
    isNewCustomer?: boolean;
  };
  onClick: (id: string) => void;
}

const OrderCard: React.FC<OrderCardProps> = ({ order, onClick }) => {
  return (
    <Card onClick={() => onClick(order.id)}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '1rem' }}>
        <span
          style={{
            fontSize: '0.6875rem',
            fontWeight: '500',
            color: 'var(--color-on-surface-variant)',
            letterSpacing: '0.05em'
          }}
        >
          ORDER #{order.id.slice(0, 5)}
        </span>
        <StatusBadge status={order.status} />
      </div>

      <div style={{ display: 'flex', alignItems: 'center', marginBottom: '1.5rem' }}>
        <div
          style={{
            width: '48px',
            height: '48px',
            backgroundColor: 'var(--color-surface-high)',
            borderRadius: '50%',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--color-on-surface)',
            fontWeight: '700',
            fontSize: '1.25rem',
            marginRight: '1rem',
            fontFamily: 'var(--font-display)'
          }}
        >
          {order.ownerName[0]}
        </div>
        <div>
          <h3
            style={{
              margin: 0,
              fontSize: '1.125rem',
              fontWeight: '700',
              color: 'var(--color-on-surface)',
              fontFamily: 'var(--font-display)'
            }}
          >
            {order.ownerName}
            {order.isNewCustomer && (
              <span
                style={{
                  fontSize: '0.625rem',
                  backgroundColor: '#dcfce7',
                  color: '#16a34a',
                  padding: '2px 8px',
                  borderRadius: '4px',
                  marginLeft: '8px',
                  verticalAlign: 'middle'
                }}
              >
                新客戶
              </span>
            )}
          </h3>
          <p
            style={{
              margin: '0.25rem 0 0',
              fontSize: '0.875rem',
              color: 'var(--color-on-surface-variant)'
            }}
          >
            {order.scheduledDates}
          </p>
        </div>
      </div>

      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          backgroundColor: 'var(--color-surface-low)',
          padding: '1rem',
          borderRadius: 'var(--radius-sm)'
        }}
      >
        <span
          style={{
            fontSize: '0.875rem',
            color: 'var(--color-on-surface-variant)',
            fontWeight: '500'
          }}
        >
          預估總額
        </span>
        <span style={{ fontWeight: '700', color: 'var(--color-primary)', fontSize: '1.125rem' }}>
          $ {order.totalAmount.toLocaleString()}
        </span>
      </div>

      <div
        style={{
          textAlign: 'center',
          marginTop: '1rem',
          fontSize: '0.875rem',
          color: 'var(--color-primary)',
          fontWeight: '700',
          letterSpacing: '0.02em'
        }}
      >
        點擊進入評估與報價 →
      </div>
    </Card>
  );
};

export default OrderCard;
