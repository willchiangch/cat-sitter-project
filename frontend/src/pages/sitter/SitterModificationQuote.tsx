import React, { useState } from 'react';
import Card from '../../components/ui/Card';
import { sendQuote, uploadRefundProof } from '../../api/orderApi';
import { useRole } from '../../contexts/RoleContext';

interface SitterModificationQuoteProps {
  orderId: string;
}

const SitterModificationQuote: React.FC<SitterModificationQuoteProps> = ({ orderId }) => {
  const { currentRole } = useRole();
  const [adjustedAmount, setAdjustedAmount] = useState<number>(1800);
  const [password, setPassword] = useState('');
  const [refundProofUrl, setRefundProofUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadingProof, setLoadingProof] = useState(false);
  const [quoteSent, setQuoteSent] = useState(false);
  const [proofSent, setProofSent] = useState(false);

  const sitterId = '3d498178-14c0-4376-b81e-7fb02e615dda'; // 測試用保母 ID

  const handleSendQuote = async (e: React.FormEvent) => {
    e.preventDefault();
    if (currentRole !== 'sitter') {
      alert('無保母權限，請先切換至保母角色！');
      return;
    }
    if (password !== 'password') {
      alert('二次驗證密碼錯誤！');
      return;
    }

    setLoading(true);
    try {
      await sendQuote(orderId, sitterId, {
        adjustedAmount,
        notes: '變更後金額微調'
      });
      setQuoteSent(true);
      alert('報價微調已成功送出！');
    } catch (err) {
      console.error(err);
      alert('送出報價失敗，請檢查權限或參數。');
    } finally {
      setLoading(false);
    }
  };

  const handleUploadProof = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!refundProofUrl.trim()) {
      alert('請輸入憑證 URL');
      return;
    }
    setLoadingProof(true);
    try {
      await uploadRefundProof(orderId, sitterId, refundProofUrl);
      setProofSent(true);
      alert('退款憑證已成功上傳！');
    } catch (err) {
      console.error(err);
      alert('憑證上傳失敗，請檢查參數。');
    } finally {
      setLoadingProof(false);
    }
  };

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '600px',
        margin: '0 auto'
      }}
    >
      <h2
        style={{
          fontSize: '1.75rem',
          fontWeight: '700',
          color: 'var(--color-on-surface)',
          fontFamily: 'var(--font-display)',
          marginBottom: '2rem'
        }}
      >
        保母變更審核與報價
      </h2>

      {currentRole !== 'sitter' && (
        <div
          style={{
            padding: '1rem',
            backgroundColor: '#f8d7da',
            color: '#721c24',
            borderRadius: 'var(--radius-sm)',
            marginBottom: '1.5rem',
            fontWeight: '600'
          }}
        >
          ⚠️ 警告：當前角色非保母，無法調用報價與憑證 API。請先在 Demo 首頁切換為保母。
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
        <Card>
          <h3
            style={{
              margin: '0 0 1.5rem 0',
              fontSize: '1.25rem',
              fontFamily: 'var(--font-display)'
            }}
          >
            變更報價調整
          </h3>
          {quoteSent ? (
            <div
              style={{
                textAlign: 'center',
                padding: '1rem',
                color: 'var(--color-primary)',
                fontWeight: '600'
              }}
              data-testid="quote-success-banner"
            >
              ✓ 報價已送出 (總額: $ {adjustedAmount.toLocaleString()})
            </div>
          ) : (
            <form
              onSubmit={handleSendQuote}
              style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>微調後最終總額</label>
                <input
                  type="number"
                  value={adjustedAmount}
                  onChange={(e) => setAdjustedAmount(Number(e.target.value))}
                  style={{
                    padding: '0.75rem',
                    borderRadius: 'var(--radius-sm)',
                    border: 'none',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface)'
                  }}
                  data-testid="quote-amount-input"
                  required
                />
              </div>

              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label
                  style={{ fontSize: '0.875rem', fontWeight: '700', color: 'var(--color-primary)' }}
                >
                  🛡️ 二次安全認證密碼
                </label>
                <input
                  type="password"
                  placeholder="請輸入保母帳號密碼"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  style={{
                    padding: '0.75rem',
                    borderRadius: 'var(--radius-sm)',
                    border: 'none',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface)'
                  }}
                  data-testid="quote-password-input"
                  required
                />
              </div>

              <button
                type="submit"
                disabled={loading || currentRole !== 'sitter'}
                className="btn-primary"
                style={{ width: '100%', padding: '1rem', marginTop: '0.5rem' }}
                data-testid="quote-submit-btn"
              >
                {loading ? '送出報價中...' : '確認送出微調報價'}
              </button>
            </form>
          )}
        </Card>

        <Card>
          <h3
            style={{
              margin: '0 0 1.5rem 0',
              fontSize: '1.25rem',
              fontFamily: 'var(--font-display)'
            }}
          >
            退款轉帳憑證上傳 (若涉及退款)
          </h3>
          {proofSent ? (
            <div
              style={{
                textAlign: 'center',
                padding: '1rem',
                color: 'var(--color-primary)',
                fontWeight: '600'
              }}
              data-testid="refund-proof-success-banner"
            >
              ✓ 退款憑證已成功上傳
            </div>
          ) : (
            <form
              onSubmit={handleUploadProof}
              style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>
                  憑證 / 收據圖片網址 (GCP Storage)
                </label>
                <input
                  type="text"
                  placeholder="https://storage.googleapis.com/proof/refund_123.jpg"
                  value={refundProofUrl}
                  onChange={(e) => setRefundProofUrl(e.target.value)}
                  style={{
                    padding: '0.75rem',
                    borderRadius: 'var(--radius-sm)',
                    border: 'none',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface)'
                  }}
                  data-testid="refund-proof-input"
                  required
                />
              </div>

              <button
                type="submit"
                disabled={loadingProof || currentRole !== 'sitter'}
                className="btn-primary"
                style={{ width: '100%', padding: '1rem', marginTop: '0.5rem' }}
                data-testid="refund-proof-submit-btn"
              >
                {loadingProof ? '上傳中...' : '確認上傳憑證'}
              </button>
            </form>
          )}
        </Card>
      </div>
    </div>
  );
};

export default SitterModificationQuote;
