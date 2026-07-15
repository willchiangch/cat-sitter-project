import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import Card from '../../components/ui/Card';
import { ChevronLeft, PlusCircle, MinusCircle } from 'lucide-react';
import { getOrderDetail, confirmOrder, sendOrderQuote, rejectOrder } from '../../api/orderApi';
import type { OrderDetailResponseDto } from '../../api/orderApi';
import { useCurrentUser } from '../../hooks/useCurrentUser';

const generateUUID = () => {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0,
      v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
};

interface OrderEvalViewProps {
  orderId: string;
}

const OrderEvalView: React.FC<OrderEvalViewProps> = ({ orderId }) => {
  const navigate = useNavigate();
  const { userId: sitterId } = useCurrentUser();

  const [order, setOrder] = useState<OrderDetailResponseDto | null>(null);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState(false);

  const [addFee, setAddFee] = useState(0);
  const [discountFee, setDiscountFee] = useState(0);
  const [adjustmentReason, setAdjustmentReason] = useState('');

  const [showRejectPanel, setShowRejectPanel] = useState(false);
  const [rejectReason, setRejectReason] = useState('');

  const fetchOrder = async () => {
    try {
      setError(null);
      const data = await getOrderDetail(orderId);
      setOrder(data);
    } catch (err: any) {
      console.error(err);
      setError(err.response?.data?.message || '讀取訂單失敗');
    } finally {
      setFetchLoading(false);
    }
  };

  useEffect(() => {
    fetchOrder();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId]);

  const baseTotal = order?.totalAmount || 0;
  const adjustment = addFee - discountFee;
  const finalTotal = baseTotal + adjustment;

  const handleAcceptOriginal = async () => {
    if (!window.confirm('確定要以原價直接接下此訂單嗎？')) return;
    setActionLoading(true);
    try {
      await confirmOrder(orderId, sitterId);
      alert('已成功接單，訂單進入待付款狀態！');
      navigate('/sitter/orders');
    } catch (err: any) {
      console.error(err);
      alert(err.response?.data?.message || '接單失敗，請稍後再試。');
    } finally {
      setActionLoading(false);
    }
  };

  const handleSendQuote = async () => {
    if (!order || order.version === undefined) return;
    if (finalTotal < 0) {
      alert('報價調整後總額不可為負數');
      return;
    }
    if (!adjustmentReason.trim()) {
      alert('有加價/折扣調整時請填寫調整原因，飼主會看到');
      return;
    }
    setActionLoading(true);
    try {
      await sendOrderQuote(
        orderId,
        sitterId,
        {
          adjustmentAmount: adjustment,
          expectedTotalAmount: finalTotal,
          adjustmentReason: adjustmentReason.trim(),
          version: order.version
        },
        generateUUID()
      );
      alert('報價已成功送出！');
      navigate('/sitter/orders');
    } catch (err: any) {
      console.error(err);
      alert(err.response?.data?.message || '報價送出失敗，請稍後再試。');
    } finally {
      setActionLoading(false);
    }
  };

  const handleReject = async () => {
    if (!order || order.version === undefined) return;
    if (!window.confirm('確定要拒絕此訂單嗎？拒絕後飼主將收到通知，此動作無法復原。')) return;
    setActionLoading(true);
    try {
      await rejectOrder(
        orderId,
        sitterId,
        {
          rejectReason: rejectReason.trim() || undefined,
          version: order.version
        },
        generateUUID()
      );
      alert('已拒絕此訂單。');
      navigate('/sitter/orders');
    } catch (err: any) {
      console.error(err);
      alert(err.response?.data?.message || '操作失敗，請稍後再試。');
    } finally {
      setActionLoading(false);
    }
  };

  if (fetchLoading) {
    return (
      <div
        style={{ padding: '2rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        載入中...
      </div>
    );
  }

  if (error || !order) {
    return (
      <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--color-error)' }}>
        {error || '找不到此訂單'}
      </div>
    );
  }

  if (order.status !== 'PENDING') {
    return (
      <div
        style={{ padding: '2rem', textAlign: 'center', color: 'var(--color-on-surface-variant)' }}
      >
        此訂單目前狀態為「{order.status}」，非待評估狀態，無法在此頁面操作。
      </div>
    );
  }

  const items = order.items || [];

  return (
    <div style={{ padding: '2rem 1.5rem', maxWidth: '640px', margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: '2.5rem' }}>
        <button
          onClick={() => navigate('/sitter/orders')}
          style={{
            border: 'none',
            background: 'var(--color-surface-low)',
            color: 'var(--color-on-surface)',
            borderRadius: '50%',
            width: '40px',
            height: '40px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            cursor: 'pointer',
            marginRight: '1rem'
          }}
        >
          <ChevronLeft size={24} />
        </button>
        <h2
          style={{
            fontSize: '1.5rem',
            fontWeight: '700',
            color: 'var(--color-on-surface)',
            fontFamily: 'var(--font-display)',
            margin: 0
          }}
        >
          新訂單評估{' '}
          <span
            style={{
              fontSize: '1rem',
              fontWeight: '500',
              color: 'var(--color-on-surface-variant)',
              marginLeft: '0.5rem'
            }}
          >
            #{order.id.slice(0, 8)}
          </span>
        </h2>
      </div>

      <Card style={{ marginBottom: '2rem' }}>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '2rem' }}>
          <div
            style={{
              width: '48px',
              height: '48px',
              backgroundColor: 'var(--color-surface-high)',
              borderRadius: '50%',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontWeight: '700',
              color: 'var(--color-on-surface)',
              marginRight: '1rem',
              fontFamily: 'var(--font-display)',
              fontSize: '1.25rem'
            }}
          >
            {order.ownerName?.[0] || '?'}
          </div>
          <div>
            <h3
              style={{
                margin: 0,
                fontSize: '1.125rem',
                fontWeight: '700',
                fontFamily: 'var(--font-display)'
              }}
              data-testid="sitter-order-eval-owner-name"
            >
              {order.ownerName || '飼主'}
            </h3>
          </div>
        </div>

        <div
          style={{
            padding: '1.5rem',
            backgroundColor: 'var(--color-surface-low)',
            borderRadius: 'var(--radius-sm)',
            marginBottom: '1.5rem'
          }}
        >
          <h4
            style={{
              margin: '0 0 1rem',
              fontSize: '0.9rem',
              fontWeight: '700',
              fontFamily: 'var(--font-display)',
              color: 'var(--color-on-surface-variant)'
            }}
          >
            預約項目明細
          </h4>
          {items.map((item: any, idx: number) => (
            <div
              key={idx}
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                fontSize: '0.875rem',
                padding: '0.5rem 0',
                borderBottom: idx < items.length - 1 ? '1px solid var(--color-outline-variant)' : 'none'
              }}
            >
              <span>
                {item.serviceName}
                {item.dates && item.dates.length > 0 ? ` · ${item.dates.length} 天` : ''}
                {item.timesPerDay ? ` · 每天 ${item.timesPerDay} 次` : ''}
              </span>
              <span style={{ fontWeight: '600' }}>
                $ {(item.unitPrice * item.quantity).toLocaleString()}
              </span>
            </div>
          ))}
        </div>

        <div
          style={{
            padding: '1.5rem',
            backgroundColor: 'var(--color-surface-low)',
            borderRadius: 'var(--radius-sm)'
          }}
        >
          <h4
            style={{
              margin: '0 0 1.5rem',
              fontSize: '1rem',
              fontWeight: '700',
              fontFamily: 'var(--font-display)',
              color: 'var(--color-on-surface)'
            }}
          >
            💰 報價微調
          </h4>

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontSize: '0.875rem',
              marginBottom: '1rem',
              color: 'var(--color-on-surface-variant)',
              fontWeight: '500'
            }}
          >
            <span>飼主原始下單金額</span>
            <span>$ {baseTotal.toLocaleString()}</span>
          </div>

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontSize: '0.875rem',
              marginBottom: '1rem',
              color: 'var(--color-primary)',
              alignItems: 'center',
              fontWeight: '600'
            }}
          >
            <span style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <PlusCircle size={16} /> 加價
            </span>
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <span style={{ marginRight: '0.5rem' }}>+ $</span>
              <input
                type="number"
                min={0}
                value={addFee}
                onChange={(e) => setAddFee(Math.max(0, Number(e.target.value)))}
                style={{
                  width: '80px',
                  border: '1px solid var(--color-outline-variant)',
                  borderRadius: 'var(--radius-sm)',
                  padding: '0.5rem',
                  textAlign: 'right',
                  background: 'var(--color-surface-lowest)',
                  fontWeight: '700'
                }}
                data-testid="sitter-order-eval-input-add-fee"
              />
            </div>
          </div>

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontSize: '0.875rem',
              marginBottom: '1.5rem',
              color: 'var(--color-error)',
              alignItems: 'center',
              fontWeight: '600'
            }}
          >
            <span style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <MinusCircle size={16} /> 折扣
            </span>
            <div style={{ display: 'flex', alignItems: 'center' }}>
              <span style={{ marginRight: '0.5rem' }}>- $</span>
              <input
                type="number"
                min={0}
                value={discountFee}
                onChange={(e) => setDiscountFee(Math.max(0, Number(e.target.value)))}
                style={{
                  width: '80px',
                  border: '1px solid var(--color-outline-variant)',
                  borderRadius: 'var(--radius-sm)',
                  padding: '0.5rem',
                  textAlign: 'right',
                  background: 'var(--color-surface-lowest)',
                  fontWeight: '700'
                }}
                data-testid="sitter-order-eval-input-discount"
              />
            </div>
          </div>

          {adjustment !== 0 && (
            <div style={{ marginBottom: '1rem' }}>
              <input
                type="text"
                placeholder="調整原因 (飼主會看到，例如：距離較遠加收車馬費)"
                value={adjustmentReason}
                onChange={(e) => setAdjustmentReason(e.target.value)}
                style={{
                  width: '100%',
                  boxSizing: 'border-box',
                  padding: '0.625rem',
                  borderRadius: 'var(--radius-sm)',
                  border: '1px solid var(--color-outline-variant)',
                  background: 'var(--color-surface-lowest)'
                }}
                data-testid="sitter-order-eval-input-reason"
              />
            </div>
          )}

          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              fontWeight: '700',
              fontSize: '1.5rem',
              marginTop: '1.5rem',
              paddingTop: '1.5rem',
              borderTop: '2px solid var(--color-surface-high)',
              color: finalTotal < 0 ? 'var(--color-error)' : 'var(--color-on-surface)',
              fontFamily: 'var(--font-display)'
            }}
          >
            <span>報價總計</span>
            <span data-testid="sitter-order-eval-total">$ {finalTotal.toLocaleString()}</span>
          </div>
        </div>
      </Card>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
        {adjustment === 0 ? (
          <Button
            className="btn-primary"
            style={{ padding: '1rem' }}
            onClick={handleAcceptOriginal}
            disabled={actionLoading}
            data-testid="sitter-order-eval-btn-accept-original"
          >
            {actionLoading ? '處理中...' : '原價直接接受'}
          </Button>
        ) : (
          <Button
            className="btn-primary"
            style={{ padding: '1rem' }}
            onClick={handleSendQuote}
            disabled={actionLoading}
            data-testid="sitter-order-eval-btn-confirm"
          >
            {actionLoading ? '送出中...' : '發送調整後報價'}
          </Button>
        )}
        {!showRejectPanel ? (
          <Button
            variant="danger-outline"
            style={{ borderRadius: '9999px', padding: '1rem' }}
            onClick={() => setShowRejectPanel(true)}
            disabled={actionLoading}
            data-testid="sitter-order-eval-btn-reject"
          >
            拒絕接單
          </Button>
        ) : (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '0.75rem',
              padding: '1rem',
              borderRadius: 'var(--radius-sm)',
              border: '1px solid var(--color-outline-variant)'
            }}
          >
            <textarea
              value={rejectReason}
              onChange={(e) => setRejectReason(e.target.value)}
              placeholder="請輸入拒絕原因 (選填，飼主會看到)"
              maxLength={500}
              style={{
                width: '100%',
                boxSizing: 'border-box',
                height: '80px',
                padding: '0.625rem',
                borderRadius: 'var(--radius-sm)',
                border: '1px solid var(--color-outline-variant)',
                background: 'var(--color-surface-lowest)',
                resize: 'none',
                fontFamily: 'inherit'
              }}
              data-testid="sitter-order-eval-input-reject-reason"
            />
            <div style={{ display: 'flex', gap: '0.75rem' }}>
              <Button
                variant="danger-outline"
                style={{ flex: 1, padding: '0.75rem' }}
                onClick={handleReject}
                disabled={actionLoading}
                data-testid="sitter-order-eval-btn-submit-reject"
              >
                {actionLoading ? '處理中...' : '確定拒絕'}
              </Button>
              <Button
                variant="secondary"
                style={{ flex: 1, padding: '0.75rem' }}
                onClick={() => {
                  setShowRejectPanel(false);
                  setRejectReason('');
                }}
                disabled={actionLoading}
              >
                取消
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default OrderEvalView;
