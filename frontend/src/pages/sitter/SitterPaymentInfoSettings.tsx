import React, { useState, useEffect } from 'react';
import Card from '../../components/ui/Card';
import { getSitterPaymentInfo, updateSitterPaymentInfo } from '../../api/orderApi';
import type { BankAccountInfo } from '../../api/orderApi';

const SitterPaymentInfoSettings: React.FC = () => {
  const [bankCode, setBankCode] = useState('');
  const [bankBranch, setBankBranch] = useState('');
  const [bankAccount, setBankAccount] = useState('');
  const [bankPayeeName, setBankPayeeName] = useState('');
  const [loading, setLoading] = useState(false);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    const fetchInfo = async () => {
      try {
        const info = await getSitterPaymentInfo();
        setBankCode(info.bankCode || '');
        setBankBranch(info.bankBranch || '');
        setBankAccount(info.bankAccount || '');
        setBankPayeeName(info.bankPayeeName || '');
      } catch (err) {
        console.error('Failed to fetch payment info:', err);
        setMessage({ type: 'error', text: '取得收款帳戶資訊失敗' });
      } finally {
        setFetchLoading(false);
      }
    };
    fetchInfo();
  }, []);

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setMessage(null);

    // 前端校驗
    if (!/^\d{3}$/.test(bankCode)) {
      setMessage({ type: 'error', text: '銀行代碼必須為 3 碼數字' });
      return;
    }
    if (!bankBranch.trim()) {
      setMessage({ type: 'error', text: '分行名稱不得為空' });
      return;
    }
    if (!/^\d{10,16}$/.test(bankAccount)) {
      setMessage({ type: 'error', text: '銀行帳號必須為 10 到 16 碼數字' });
      return;
    }
    if (!bankPayeeName.trim()) {
      setMessage({ type: 'error', text: '戶名不得為空' });
      return;
    }

    setLoading(true);
    try {
      const payload: BankAccountInfo = {
        bankCode,
        bankBranch,
        bankAccount,
        bankPayeeName
      };
      await updateSitterPaymentInfo(payload);
      setMessage({ type: 'success', text: '銀行轉帳帳戶資訊更新成功！' });
    } catch (err: any) {
      console.error(err);
      const errMsg = err.response?.data?.message || '更新失敗，請檢查輸入欄位格式';
      setMessage({ type: 'error', text: errMsg });
    } finally {
      setLoading(false);
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

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '520px',
        margin: '0 auto'
      }}
    >
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          marginBottom: '1.5rem'
        }}
      >
        銀行收款資訊設定
      </h2>
      <p
        style={{
          fontSize: '0.875rem',
          color: 'var(--color-on-surface-variant)',
          marginBottom: '2rem'
        }}
      >
        請填寫您的銀行帳戶。飼主選擇線下轉帳付款時，系統將在付款頁面顯示此資訊。您的敏感情財務資料在資料庫中將進行高強度
        AES-256 加密存儲。
      </p>

      <Card>
        <form
          onSubmit={handleSave}
          style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
        >
          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              銀行代碼 (3 碼)
            </label>
            <input
              type="text"
              value={bankCode}
              onChange={(e) => setBankCode(e.target.value)}
              placeholder="例如 822"
              maxLength={3}
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)'
              }}
              data-testid="input-sitter-bank-code"
            />
          </div>

          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              分行名稱
            </label>
            <input
              type="text"
              value={bankBranch}
              onChange={(e) => setBankBranch(e.target.value)}
              placeholder="例如 忠孝分行"
              maxLength={100}
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)'
              }}
              data-testid="input-sitter-bank-branch"
            />
          </div>

          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              銀行帳號 (10 ~ 16 碼)
            </label>
            <input
              type="text"
              value={bankAccount}
              onChange={(e) => setBankAccount(e.target.value)}
              placeholder="請輸入純數字帳號"
              maxLength={16}
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)'
              }}
              data-testid="input-sitter-bank-account"
            />
          </div>

          <div>
            <label
              style={{
                display: 'block',
                fontSize: '0.875rem',
                fontWeight: '600',
                marginBottom: '0.5rem',
                color: 'var(--color-on-surface)'
              }}
            >
              收款戶名
            </label>
            <input
              type="text"
              value={bankPayeeName}
              onChange={(e) => setBankPayeeName(e.target.value)}
              placeholder="例如 王小明"
              maxLength={100}
              style={{
                width: '100%',
                padding: '0.75rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                backgroundColor: 'var(--color-surface)',
                color: 'var(--color-on-surface)'
              }}
              data-testid="input-sitter-bank-payee"
            />
          </div>

          {message && (
            <div
              style={{
                padding: '0.75rem',
                borderRadius: '6px',
                fontSize: '0.875rem',
                fontWeight: '500',
                backgroundColor: message.type === 'success' ? '#dcfce7' : '#fee2e2',
                color: message.type === 'success' ? '#16a34a' : '#ef4444'
              }}
              data-testid="sitter-bank-message"
            >
              {message.text}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="btn-primary"
            style={{ marginTop: '0.5rem', padding: '0.75rem' }}
            data-testid="btn-save-bank-info"
          >
            {loading ? '儲存中...' : '儲存帳戶資訊'}
          </button>
        </form>
      </Card>
    </div>
  );
};

export default SitterPaymentInfoSettings;
