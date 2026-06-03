import React, { useState, useEffect } from 'react';
import StatusBadge from '../../components/ui/StatusBadge';
import Card from '../../components/ui/Card';
import { getOrderDetail, verifyPayment, rejectPayment } from '../../api/orderApi';

type TabType = 'evaluating' | 'ongoing' | 'history';

const SitterOrders: React.FC = () => {
  const [activeTab, setActiveTab] = useState<TabType>('evaluating');
  const [dbOrder, setDbOrder] = useState<any>(null);
  const [loading, setLoading] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [showRejectForm, setShowRejectForm] = useState(false);

  const orderId = 'a1023000-0000-0000-0000-000000000000'; // 預設測試訂單 ID

  const fetchOrder = async () => {
    try {
      const data = await getOrderDetail(orderId);
      setDbOrder(data);
    } catch (err) {
      console.error('Failed to load DB order for sitter view:', err);
    }
  };

  useEffect(() => {
    fetchOrder();
  }, []);

  const handleVerify = async () => {
    setLoading(true);
    try {
      await verifyPayment(orderId);
      await fetchOrder();
      alert('入帳核對確認成功！');
    } catch (err) {
      console.error(err);
      alert('操作失敗，請稍後再試。');
    } finally {
      setLoading(false);
    }
  };

  const handleReject = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!rejectReason.trim()) {
      alert('請填寫退回原因');
      return;
    }
    setLoading(true);
    try {
      await rejectPayment(orderId, rejectReason);
      setRejectReason('');
      setShowRejectForm(false);
      await fetchOrder();
      alert('已成功駁回該憑證，訂單已退回待付款狀態。');
    } catch (err: any) {
      console.error(err);
      const errMsg = err.response?.data?.message || '駁回失敗，請稍後再試';
      alert(errMsg);
    } finally {
      setLoading(false);
    }
  };

  const mockOrders = [
    {
      id: orderId,
      ownerName: '陳先生 (愛貓飼主)',
      status: dbOrder ? dbOrder.status : 'PENDING_PAYMENT',
      totalAmount: dbOrder ? dbOrder.totalAmount : 2400,
      scheduledDates: '2026-05-25 ~ 2026-05-29 (共 5 天)',
      isNewCustomer: true,
      paymentProofUrl: dbOrder?.paymentProofUrl,
      paymentProofLastFive: dbOrder?.paymentProofLastFive
    }
  ];

  const filteredOrders = mockOrders.filter((order) => {
    if (activeTab === 'evaluating') return order.status === 'PENDING';
    if (activeTab === 'ongoing')
      return ['PENDING_PAYMENT', 'PAID', 'CONFIRMED', 'MODIFYING', 'REFUND_VERIFY'].includes(
        order.status
      );
    return order.status === 'COMPLETED' || order.status === 'CANCELLED';
  });

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '640px', margin: '0 auto' }}>
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
          訂單管理 (保母端)
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

      <div
        style={{
          display: 'flex',
          gap: '0.5rem',
          marginBottom: '2rem',
          overflowX: 'auto',
          paddingBottom: '4px'
        }}
      >
        {(['evaluating', 'ongoing', 'history'] as TabType[]).map((tab) => (
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
              transition: 'all 0.2s',
              whiteSpace: 'nowrap'
            }}
            data-testid={`sitter-orders-tab-${tab}`}
          >
            {tab === 'evaluating' ? '評估中' : tab === 'ongoing' ? '進行中' : '歷史訂單'}
          </button>
        ))}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
        {filteredOrders.length > 0 ? (
          filteredOrders.map((order) => (
            <Card key={order.id}>
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '1rem' }}>
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
                  borderRadius: 'var(--radius-sm)',
                  marginBottom: '1.5rem'
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

              {/* 保母憑證核對面板 (僅在 status === 'PAID' 時顯示) */}
              {order.status === 'PAID' && (
                <div
                  data-testid="payment-verification-panel"
                  style={{
                    borderTop: '1px solid var(--color-surface-high)',
                    paddingTop: '1.5rem',
                    marginTop: '1rem'
                  }}
                >
                  <h4 style={{ margin: '0 0 1rem 0', color: 'var(--color-on-surface)' }}>付款憑證核對</h4>
                  
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', marginBottom: '1.5rem', fontSize: '0.875rem' }}>
                    <div>飼主轉帳後五碼：<strong style={{ color: 'var(--color-primary)' }}>{order.paymentProofLastFive}</strong></div>
                    {order.paymentProofUrl && (
                      <div>
                        <div style={{ color: 'var(--color-on-surface-variant)', marginBottom: '0.5rem' }}>轉帳憑證預覽：</div>
                        <img
                          src={order.paymentProofUrl}
                          alt="Owner Payment Proof"
                          style={{
                            maxWidth: '100%',
                            maxHeight: '220px',
                            borderRadius: '6px',
                            border: '1px solid var(--color-surface-high)'
                          }}
                        />
                      </div>
                    )}
                  </div>

                  {!showRejectForm ? (
                    <div style={{ display: 'flex', gap: '1rem' }}>
                      <button
                        onClick={handleVerify}
                        disabled={loading}
                        className="btn-primary"
                        style={{ flex: 1, padding: '0.75rem' }}
                        data-testid="btn-verify-payment"
                      >
                        {loading ? '處理中...' : '確認款項入帳'}
                      </button>
                      <button
                        onClick={() => setShowRejectForm(true)}
                        disabled={loading}
                        className="btn-primary"
                        style={{
                          flex: 1,
                          padding: '0.75rem',
                          backgroundColor: 'var(--color-surface-high)',
                          color: 'var(--color-on-surface-variant)',
                          boxShadow: 'none'
                        }}
                        data-testid="btn-reject-payment"
                      >
                        駁回憑證
                      </button>
                    </div>
                  ) : (
                    <form onSubmit={handleReject} style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                      <div>
                        <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: '600', marginBottom: '0.5rem' }}>
                          駁回原因
                        </label>
                        <textarea
                          value={rejectReason}
                          onChange={(e) => setRejectReason(e.target.value)}
                          placeholder="請輸入駁回此憑證的具體原因..."
                          maxLength={500}
                          style={{
                            width: '100%',
                            height: '80px',
                            padding: '0.5rem',
                            borderRadius: '6px',
                            border: '1px solid var(--color-surface-high)',
                            backgroundColor: 'var(--color-surface)',
                            color: 'var(--color-on-surface)',
                            fontFamily: 'inherit',
                            resize: 'none'
                          }}
                          data-testid="input-reject-reason"
                        />
                      </div>
                      <div style={{ display: 'flex', gap: '0.75rem' }}>
                        <button
                          type="submit"
                          disabled={loading}
                          className="btn-primary"
                          style={{ flex: 1, padding: '0.5rem' }}
                          data-testid="btn-submit-reject"
                        >
                          {loading ? '處理中...' : '確定駁回'}
                        </button>
                        <button
                          type="button"
                          onClick={() => {
                            setShowRejectForm(false);
                            setRejectReason('');
                          }}
                          className="btn-primary"
                          style={{
                            flex: 1,
                            padding: '0.5rem',
                            backgroundColor: 'var(--color-surface-low)',
                            color: 'var(--color-on-surface-variant)',
                            boxShadow: 'none'
                          }}
                        >
                          取消
                        </button>
                      </div>
                    </form>
                  )}
                </div>
              )}
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

export default SitterOrders;
