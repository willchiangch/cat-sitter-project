import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import { modifyOrder } from '../../api/orderApi';

interface OrderModificationWizardProps {
  orderId: string;
}

const OrderModificationWizard: React.FC<OrderModificationWizardProps> = ({ orderId }) => {
  const [datesStr, setDatesStr] = useState('2026-05-26, 2026-05-27');
  const [isCancel, setIsCancel] = useState(false);
  const [loading, setLoading] = useState(false);
  const [status, setStatus] = useState<'IDLE' | 'SUCCESS' | 'ERROR'>('IDLE');
  
  // 方案有效期間 Mock 限制：2026-05-01 到 2026-05-31
  const planStart = '2026-05-01';
  const planEnd = '2026-05-31';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setStatus('IDLE');

    const dates = isCancel ? [] : datesStr.split(',').map(d => d.trim()).filter(Boolean);

    // 安全防呆：檢查日期是否超出方案範圍
    const outOfRange = dates.some(date => date < planStart || date > planEnd);
    if (outOfRange) {
      alert('⚠️ 錯誤 (PLAN_NOT_IN_RANGE)：修改的日期超出服務方案的有效期間！');
      setLoading(false);
      return;
    }

    try {
      const idempotencyKey = crypto.randomUUID();
      const items = dates.map(d => ({
        servicePlanId: '3d498178-14c0-4376-b81e-7fb02e615dda', // mock service plan ID
        scheduledDate: d
      }));

      await modifyOrder(orderId, 'OWNER', {
        items,
        totalDays: dates.length,
        dates
      }, idempotencyKey);

      setStatus('SUCCESS');
      alert('變更申請已成功提交！');
    } catch (err) {
      console.error(err);
      setStatus('ERROR');
      alert('提交變更失敗，請重試。');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '600px', margin: '0 auto' }}>
      <h2 style={{ fontSize: '1.75rem', fontWeight: '700', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)', marginBottom: '2rem' }}>
        訂單變更精靈
      </h2>

      <Card>
        {status === 'SUCCESS' ? (
          <div style={{ textAlign: 'center', padding: '2rem 0' }} data-testid="modification-success-banner">
            <div style={{ fontSize: '3rem', color: 'var(--color-primary)', marginBottom: '1rem' }}>✓</div>
            <h3 style={{ margin: 0, color: 'var(--color-primary)' }}>變更請求已送出</h3>
            <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.875rem', marginTop: '0.5rem' }}>
              保母端已收到變更提案，請靜候報價審核。
            </p>
          </div>
        ) : (
          <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            <div style={{ padding: '1rem', backgroundColor: 'var(--color-surface-low)', borderRadius: 'var(--radius-sm)', fontSize: '0.875rem' }}>
              <strong>方案限制區間：</strong> {planStart} ~ {planEnd}
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
              <input 
                type="checkbox" 
                id="cancel-booking"
                checked={isCancel} 
                onChange={(e) => setIsCancel(e.target.checked)}
                data-testid="cancel-checkbox"
              />
              <label htmlFor="cancel-booking" style={{ fontSize: '0.875rem', fontWeight: '700', color: 'var(--color-primary)', cursor: 'pointer' }}>
                申請完全取消此預約 (全額/部分退款)
              </label>
            </div>

            {!isCancel && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>變更後日期 (逗號分隔)</label>
                <input 
                  type="text" 
                  value={datesStr}
                  onChange={(e) => setDatesStr(e.target.value)}
                  placeholder="YYYY-MM-DD, YYYY-MM-DD"
                  style={{ padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)', outline: 'none' }}
                  data-testid="modify-dates-input"
                  required
                />
                <span style={{ fontSize: '0.75rem', color: 'var(--color-on-surface-variant)' }}>
                  請輸入新日期，如輸入超出區間日期將會觸發 PLAN_NOT_IN_RANGE 防禦。
                </span>
              </div>
            )}

            <button 
              type="submit" 
              disabled={loading}
              className="btn-primary" 
              style={{ width: '100%', padding: '1rem', marginTop: '1rem' }}
              data-testid="modify-submit-btn"
            >
              {loading ? '提交中...' : isCancel ? '送出取消預約申請' : '提交變更申請'}
            </button>
          </form>
        )}
      </Card>
    </div>
  );
};

export default OrderModificationWizard;
