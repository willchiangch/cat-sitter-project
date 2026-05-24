import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import { resolveDisputedOrder } from '../../api/orderApi';
import { useRole } from '../../contexts/RoleContext';

interface AdminResolvePanelProps {
  orderId: string;
}

const AdminResolvePanel: React.FC<AdminResolvePanelProps> = ({ orderId }) => {
  const { currentRole } = useRole();
  const [finalAmount, setFinalAmount] = useState<number>(2000);
  const [receiptUrl, setReceiptUrl] = useState('');
  const [reason, setReason] = useState('雙方達成和解，退還部分款項。');
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [resolved, setResolved] = useState(false);

  const handleResolve = async (e: React.FormEvent) => {
    e.preventDefault();
    if (currentRole !== 'admin') {
      alert('無管理員權限，請先切換至管理員角色！');
      return;
    }
    if (!reason.trim()) {
      alert('請填寫調解原因');
      return;
    }
    if (password !== 'password') {
      alert('二次驗證密碼錯誤！');
      return;
    }

    setLoading(true);
    try {
      await resolveDisputedOrder(orderId, {
        finalAmount,
        receiptUrl,
        reason
      });
      setResolved(true);
      alert('爭議已順利調解結案！');
    } catch (err) {
      console.error(err);
      alert('調解失敗，請檢查權限或參數。');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ padding: '2rem 1.5rem', fontFamily: 'var(--font-sans)', maxWidth: '600px', margin: '0 auto' }}>
      <h2 style={{ fontSize: '1.75rem', fontWeight: '700', color: 'var(--color-on-surface)', fontFamily: 'var(--font-display)', marginBottom: '2rem' }}>
        管理端 - 爭議調解面板
      </h2>

      {currentRole !== 'admin' && (
        <div style={{ padding: '1rem', backgroundColor: '#f8d7da', color: '#721c24', borderRadius: 'var(--radius-sm)', marginBottom: '1.5rem', fontWeight: '600' }}>
          ⚠️ 警告：當前角色非管理員，無法調用調解 API。請先在 Demo 首頁切換為管理員。
        </div>
      )}

      <Card>
        {resolved ? (
          <div style={{ textAlign: 'center', padding: '2rem 0' }} data-testid="admin-resolved-banner">
            <div style={{ fontSize: '3rem', marginBottom: '1rem' }}>✓</div>
            <h3 style={{ margin: 0, color: 'var(--color-primary)' }}>爭議已調解結案</h3>
            <p style={{ color: 'var(--color-on-surface-variant)', fontSize: '0.875rem', marginTop: '0.5rem' }}>
              訂單金額已重設為 $ {finalAmount.toLocaleString()}，款項將於 3 日後撥付。
            </p>
          </div>
        ) : (
          <form onSubmit={handleResolve} style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.25rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>案件 Order ID</label>
              <input 
                type="text" 
                value={orderId} 
                disabled 
                style={{ padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: 'none', backgroundColor: 'var(--color-surface-low)' }} 
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>最終決議金額</label>
              <input 
                type="number" 
                value={finalAmount}
                onChange={(e) => setFinalAmount(Number(e.target.value))}
                style={{ padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)' }}
                data-testid="admin-resolve-amount"
                required
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>退款憑證 / 收據連結</label>
              <input 
                type="text" 
                placeholder="https://example.com/receipt.jpg"
                value={receiptUrl}
                onChange={(e) => setReceiptUrl(e.target.value)}
                style={{ padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)' }}
                data-testid="admin-resolve-receipt"
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>調解裁決原因</label>
              <textarea 
                rows={3}
                placeholder="請輸入裁決理由與雙方協議結果..."
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                style={{ padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)', resize: 'none' }}
                data-testid="admin-resolve-reason"
                required
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', borderTop: '1px solid var(--color-surface-high)', paddingTop: '1.5rem' }}>
              <label style={{ fontSize: '0.875rem', fontWeight: '700', color: 'var(--color-primary)' }}>
                🛡️ 二次安全認證密碼
              </label>
              <input 
                type="password" 
                placeholder="請輸入管理員密碼"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                style={{ padding: '0.75rem', borderRadius: 'var(--radius-sm)', border: 'none', backgroundColor: 'var(--color-surface-low)', color: 'var(--color-on-surface)' }}
                data-testid="admin-resolve-password"
                required
              />
            </div>

            <button 
              type="submit" 
              disabled={loading || currentRole !== 'admin'}
              className="btn-primary" 
              style={{ width: '100%', padding: '1rem', marginTop: '1rem' }}
              data-testid="admin-resolve-submit-btn"
            >
              {loading ? '送出調解中...' : '確認強制結案'}
            </button>
          </form>
        )}
      </Card>
    </div>
  );
};

export default AdminResolvePanel;
