import React, { useState, useEffect } from 'react';
import Card from '../../components/ui/Card';
import StatusBadge from '../../components/ui/StatusBadge';
import { getOrderDetail } from '../../api/orderApi';

interface OwnerOrdersProps {
  setView: (view: any) => void;
}

type TabType = 'ongoing' | 'history';

const OwnerOrders: React.FC<OwnerOrdersProps> = ({ setView }) => {
  const [activeTab, setActiveTab] = useState<TabType>('ongoing');
  const [dbOrder, setDbOrder] = useState<any>(null);

  useEffect(() => {
    const fetchOrder = async () => {
      try {
        const data = await getOrderDetail('a1023000-0000-0000-0000-000000000000');
        setDbOrder(data);
      } catch (err) {
        console.error('Failed to fetch DB order in OwnerOrders:', err);
      }
    };
    fetchOrder();
  }, []);

  // 模擬飼主端訂單資料
  const mockOrders = [
    {
      id: 'a1023000-0000-0000-0000-000000000000',
      sitterName: '本地測試保母',
      status: dbOrder ? dbOrder.status : 'PENDING_PAYMENT',
      totalAmount: dbOrder ? dbOrder.totalAmount : 2400,
      scheduledDates: '2026-05-25 ~ 2026-05-29 (共 5 天)'
    },
    {
      id: 'a1024000-0000-0000-0000-000000000000',
      sitterName: '本地測試保母',
      status: 'COMPLETED',
      totalAmount: 1200,
      scheduledDates: '2026-05-10 ~ 2026-05-12 (共 3 天)'
    },
    {
      id: 'a1025000-0000-0000-0000-000000000000',
      sitterName: '本地測試保母',
      status: 'DISPUTED',
      totalAmount: 3000,
      scheduledDates: '2026-05-15 ~ 2026-05-20 (共 6 天)'
    }
  ];

  const filteredOrders = mockOrders.filter((order) => {
    if (activeTab === 'ongoing') {
      return [
        'PENDING',
        'CONFIRMED',
        'MODIFYING',
        'REFUND_VERIFY',
        'PENDING_PAYMENT',
        'PAID'
      ].includes(order.status);
    }
    return ['COMPLETED', 'CANCELLED', 'DISPUTED'].includes(order.status);
  });

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)' }}>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'baseline',
          marginBottom: '2.5rem'
        }}
      >
        <h2
          style={{
            fontSize: '2rem',
            fontWeight: '700',
            color: 'var(--color-on-surface)',
            fontFamily: 'var(--font-display)',
            margin: 0
          }}
        >
          飼主訂單管理
        </h2>
        <span
          style={{
            fontSize: '0.875rem',
            color: 'var(--color-on-surface-variant)',
            fontWeight: '500'
          }}
        >
          共 {filteredOrders.length} 筆
        </span>
      </div>

      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '2rem' }}>
        {(['ongoing', 'history'] as TabType[]).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            style={{
              padding: '0.625rem 1.25rem',
              borderRadius: '9999px',
              border: 'none',
              backgroundColor:
                activeTab === tab ? 'var(--color-primary-container)' : 'var(--color-surface-low)',
              fontSize: '0.875rem',
              fontWeight: '600',
              color: activeTab === tab ? 'var(--color-primary)' : 'var(--color-on-surface-variant)',
              cursor: 'pointer',
              transition: 'all 0.2s'
            }}
            data-testid={`owner-orders-tab-${tab}`}
          >
            {tab === 'ongoing' ? '進行中' : '歷史與爭議'}
          </button>
        ))}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
        {filteredOrders.length > 0 ? (
          filteredOrders.map((order) => (
            <Card
              key={order.id}
              onClick={() => setView({ name: 'owner-order-detail', params: { orderId: order.id } })}
            >
              <div
                style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '1rem' }}
              >
                <span
                  style={{
                    fontSize: '0.6875rem',
                    fontWeight: '500',
                    color: 'var(--color-on-surface-variant)',
                    letterSpacing: '0.05em'
                  }}
                >
                  ORDER #{order.id.slice(0, 8)}
                </span>
                <StatusBadge status={order.status} />
              </div>
              <div style={{ display: 'flex', alignItems: 'center', marginBottom: '1.5rem' }}>
                <div
                  style={{
                    width: '40px',
                    height: '40px',
                    backgroundColor: 'var(--color-surface-high)',
                    borderRadius: '50%',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    color: 'var(--color-on-surface)',
                    fontWeight: '700',
                    marginRight: '1rem',
                    fontFamily: 'var(--font-display)'
                  }}
                >
                  {order.sitterName[0]}
                </div>
                <div>
                  <h3
                    style={{
                      margin: 0,
                      fontSize: '1rem',
                      fontWeight: '700',
                      color: 'var(--color-on-surface)'
                    }}
                  >
                    {order.sitterName}
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
                  訂單總額
                </span>
                <span
                  style={{ fontWeight: '700', color: 'var(--color-primary)', fontSize: '1.125rem' }}
                >
                  $ {order.totalAmount.toLocaleString()}
                </span>
              </div>
            </Card>
          ))
        ) : (
          <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>
            目前沒有相關訂單
          </div>
        )}
      </div>
    </div>
  );
};

export default OwnerOrders;
