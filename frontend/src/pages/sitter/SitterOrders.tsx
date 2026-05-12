import React, { useState } from 'react';
import OrderCard from '../../components/orders/OrderCard';

type TabType = 'evaluating' | 'ongoing' | 'history';

const SitterOrders: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabType>('evaluating');

  // 模擬資料 (未來串接 React Query)
  const mockOrders = [
    { id: 'A1023', ownerName: '陳先生', status: 'PENDING', totalAmount: 2400, scheduledDates: '10/01-10/05 (共 5 天)', isNewCustomer: true },
    { id: 'A1018', ownerName: '林小姐 (橘子家)', status: 'CONFIRMED', totalAmount: 1800, scheduledDates: '10/10-10/12 (共 3 天)', isNewCustomer: false },
  ];

  const filteredOrders = mockOrders.filter(order => {
    if (activeTab === 'evaluating') return order.status === 'PENDING';
    if (activeTab === 'ongoing') return ['PENDING_PAYMENT', 'PAID', 'CONFIRMED'].includes(order.status);
    return order.status === 'COMPLETED' || order.status === 'CANCELLED';
  });

  return (
    <div style={{ padding: '2rem 1.5rem' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: '2.5rem' }}>
        <h2 style={{ fontSize: '2rem', fontWeight: '700', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)', margin: 0 }}>訂單管理</h2>
        <span style={{ fontSize: '0.875rem', color: 'var(--color-on-surface-variant)', fontWeight: '500' }}>共 {filteredOrders.length} 筆</span>
      </div>
      
      <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '2rem', overflowX: 'auto', paddingBottom: '4px' }}>
        {(['evaluating', 'ongoing', 'history'] as TabType[]).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            style={{
              padding: '0.625rem 1.25rem',
              borderRadius: '9999px',
              border: 'none',
              backgroundColor: activeTab === tab ? 'var(--color-primary-container)' : 'var(--color-surface-low)',
              fontSize: '0.875rem',
              fontWeight: '600',
              color: activeTab === tab ? 'var(--color-primary)' : 'var(--color-on-surface-variant)',
              cursor: 'pointer',
              transition: 'all 0.2s',
              whiteSpace: 'nowrap'
            }}
            data-testid={`sitter-orders-tab-${tab}`}
          >
            {tab === 'evaluating' ? '評估中' : tab === 'ongoing' ? '進行中' : '歷史訂單'}
          </button>
        ))}
      </div>

      <div>
        {filteredOrders.length > 0 ? (
          filteredOrders.map(order => (
            <OrderCard 
              key={order.id} 
              order={order} 
              onClick={(id) => console.log('Go to order eval:', id)} 
            />
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

export default SitterOrders;
