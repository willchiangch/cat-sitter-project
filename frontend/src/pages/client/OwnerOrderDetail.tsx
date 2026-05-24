import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import StatusBadge from '../../components/ui/StatusBadge';
import OrderDisputeModal from '../../components/orders/OrderDisputeModal';
import { completeOrder, disputeOrder } from '../../api/orderApi';

interface OwnerOrderDetailProps {
  orderId: string;
  setView: (view: any) => void;
}

const OwnerOrderDetail: React.FC<OwnerOrderDetailProps> = ({ orderId, setView }) => {
  const [status, setStatus] = useState('CONFIRMED');
  const [isDisputeOpen, setIsDisputeOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  const ownerId = '1031efbc-583a-4062-9a35-15706a3384c6'; // 測試用 Owner ID

  const handleComplete = async () => {
    if (!window.confirm('確定要將此訂單結案嗎？這將會放行款項給保母。')) return;
    setLoading(true);
    try {
      await completeOrder(orderId, ownerId);
      setStatus('COMPLETED');
      alert('訂單已順利結案！');
    } catch (err) {
      console.error(err);
      alert('結案失敗，請稍後再試。');
    } finally {
      setLoading(false);
    }
  };

  const handleDisputeSubmit = async (category: string, description: string) => {
    try {
      await disputeOrder(orderId, ownerId, category, description);
      setStatus('DISPUTED');
      alert('爭議申報成功，請靜候管理員處理。');
    } catch (err) {
      console.error(err);
      alert('申報失敗，請稍後再試。');
      throw err;
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '600px', margin: '0 auto' }}>
      <button 
        onClick={() => setView({ name: 'owner-orders' })}
        style={{
          border: 'none',
          background: 'none',
          color: 'var(--color-primary)',
          fontSize: '0.875rem',
          fontWeight: '600',
          cursor: 'pointer',
          padding: 0,
          marginBottom: '1.5rem',
          display: 'flex',
          alignItems: 'center',
          gap: '4px'
        }}
      >
        ← 返回訂單列表
      </button>

      <Card>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '1.5rem', alignItems: 'center' }}>
          <span style={{ fontSize: '0.75rem', fontWeight: '500', color: 'var(--color-on-surface-variant)', letterSpacing: '0.05em' }}>
            ORDER #{orderId.slice(0, 8)}
          </span>
          <StatusBadge status={status} />
        </div>

        <div style={{ borderBottom: '1px solid var(--color-surface-high)', paddingBottom: '1.5rem', marginBottom: '1.5rem' }}>
          <h3 style={{ margin: '0 0 0.5rem 0', fontSize: '1.25rem', fontWeight: '700', color: 'var(--color-on-surface)' }}>服務內容</h3>
          <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>保母: 本地測試保母</p>
          <p style={{ margin: '0.25rem 0 0 0', fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>時程: 2026-05-25 ~ 2026-05-29 (共 5 天)</p>
        </div>

        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '2.5rem' }}>
          <span style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--color-on-surface)' }}>訂單金額</span>
          <span style={{ fontSize: '1.5rem', fontWeight: '800', color: 'var(--color-primary)' }}>$ 2,400</span>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {status === 'CONFIRMED' && (
            <>
              <button 
                onClick={handleComplete}
                disabled={loading}
                className="btn-primary"
                style={{ width: '100%', padding: '1rem' }}
                data-testid="complete-order-btn"
              >
                {loading ? '處理中...' : '確認結案 (完成預約)'}
              </button>
              <button 
                onClick={() => setIsDisputeOpen(true)}
                disabled={loading}
                className="btn-primary"
                style={{ width: '100%', padding: '1rem', backgroundColor: 'var(--color-surface-high)', color: 'var(--color-on-surface-variant)', boxShadow: 'none' }}
                data-testid="dispute-order-btn"
              >
                申報爭議
              </button>
            </>
          )}

          {status === 'DISPUTED' && (
            <div style={{ textAlign: 'center', padding: '1rem', backgroundColor: '#fef3c7', color: '#d97706', borderRadius: 'var(--radius-sm)', fontSize: '0.875rem', fontWeight: '600' }} data-testid="disputed-status-banner">
              ⚠️ 訂單正處於爭議調解中，管理員將主動介入。
            </div>
          )}

          {status === 'COMPLETED' && (
            <div style={{ textAlign: 'center', padding: '1rem', backgroundColor: '#dcfce7', color: '#16a34a', borderRadius: 'var(--radius-sm)', fontSize: '0.875rem', fontWeight: '600' }} data-testid="completed-status-banner">
              ✓ 訂單已順利結案，感謝您的使用！
            </div>
          )}
        </div>
      </Card>

      <OrderDisputeModal 
        isOpen={isDisputeOpen} 
        onClose={() => setIsDisputeOpen(false)} 
        onSubmit={handleDisputeSubmit} 
      />
    </div>
  );
};

export default OwnerOrderDetail;
