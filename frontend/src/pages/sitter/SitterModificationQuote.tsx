import React, { useEffect, useState } from 'react';
import Card from '../../components/ui/Card';
import {
  getActiveModificationRequest,
  quoteModification,
  rejectModification,
  uploadRefundProof
} from '../../api/orderApi';
import type { ModificationRequestDetailDto } from '../../api/orderApi';
import { useRole } from '../../contexts/RoleContext';
import { useCurrentUser } from '../../hooks/useCurrentUser';

interface SitterModificationQuoteProps {
  orderId: string;
}

const SitterModificationQuote: React.FC<SitterModificationQuoteProps> = ({ orderId }) => {
  const { currentRole } = useRole();
  const { userId: sitterId } = useCurrentUser();
  const [modRequest, setModRequest] = useState<ModificationRequestDetailDto | null>(null);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [newTotalAmount, setNewTotalAmount] = useState<number>(0);
  const [refundProofUrl, setRefundProofUrl] = useState('');
  const [loading, setLoading] = useState(false);
  const [loadingReject, setLoadingReject] = useState(false);
  const [loadingProof, setLoadingProof] = useState(false);
  const [quoteSent, setQuoteSent] = useState(false);
  const [rejected, setRejected] = useState(false);
  const [proofSent, setProofSent] = useState(false);

  useEffect(() => {
    getActiveModificationRequest(orderId)
      .then((detail) => {
        setModRequest(detail);
        setNewTotalAmount(detail.newTotalAmount);
      })
      .catch((err) => {
        console.error(err);
        setFetchError('找不到進行中的變更請求，可能已被處理或不存在。');
      })
      .finally(() => setFetchLoading(false));
  }, [orderId]);

  const handleSendQuote = async (e: React.FormEvent) => {
    e.preventDefault();
    if (currentRole !== 'sitter') {
      alert('無保母權限，請先切換至保母角色！');
      return;
    }
    if (!modRequest) return;

    setLoading(true);
    try {
      const idempotencyKey = crypto.randomUUID();
      await quoteModification(
        orderId,
        modRequest.id,
        { newTotalAmount, version: modRequest.orderVersion },
        idempotencyKey
      );
      setQuoteSent(true);
      alert('報價已成功送出，等待飼主確認！');
    } catch (err) {
      console.error(err);
      alert('送出報價失敗，請檢查權限或參數。');
    } finally {
      setLoading(false);
    }
  };

  const handleReject = async () => {
    if (currentRole !== 'sitter') {
      alert('無保母權限，請先切換至保母角色！');
      return;
    }
    if (!modRequest) return;
    if (!window.confirm('確定要拒絕此變更請求嗎？訂單將恢復原狀態。')) return;

    setLoadingReject(true);
    try {
      const idempotencyKey = crypto.randomUUID();
      await rejectModification(orderId, modRequest.id, idempotencyKey);
      setRejected(true);
      alert('已拒絕此變更請求，訂單已恢復原狀態！');
    } catch (err) {
      console.error(err);
      alert('拒絕變更失敗，請稍後再試。');
    } finally {
      setLoadingReject(false);
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

      {fetchLoading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : fetchError || !modRequest ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }} data-testid="mod-quote-error">
          {fetchError}
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
          <Card>
            <h3
              style={{
                margin: '0 0 1.5rem 0',
                fontSize: '1.25rem',
                fontFamily: 'var(--font-display)'
              }}
            >
              變更報價審核
            </h3>
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                padding: '0 0 1.25rem',
                fontSize: '0.875rem',
                color: 'var(--color-on-surface-variant)'
              }}
            >
              <span>目前訂單總額：</span>
              <strong data-testid="mod-quote-current-total">
                $ {modRequest.currentOrderTotalAmount.toLocaleString()}
              </strong>
            </div>

            {rejected ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '1rem',
                  color: 'var(--color-on-surface-variant)',
                  fontWeight: '600'
                }}
                data-testid="reject-success-banner"
              >
                ✓ 已拒絕此變更請求
              </div>
            ) : quoteSent ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '1rem',
                  color: 'var(--color-primary)',
                  fontWeight: '600'
                }}
                data-testid="quote-success-banner"
              >
                ✓ 報價已送出 (總額: $ {newTotalAmount.toLocaleString()})
              </div>
            ) : (
              <form
                onSubmit={handleSendQuote}
                style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                  <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>變更後最終總額</label>
                  <input
                    type="number"
                    value={newTotalAmount}
                    onChange={(e) => setNewTotalAmount(Number(e.target.value))}
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

                <button
                  type="submit"
                  disabled={loading || currentRole !== 'sitter'}
                  className="btn-primary"
                  style={{ width: '100%', padding: '1rem', marginTop: '0.5rem' }}
                  data-testid="quote-submit-btn"
                >
                  {loading ? '送出報價中...' : '確認送出報價'}
                </button>

                <button
                  type="button"
                  onClick={handleReject}
                  disabled={loadingReject || currentRole !== 'sitter'}
                  style={{
                    width: '100%',
                    padding: '1rem',
                    backgroundColor: 'var(--color-surface-low)',
                    color: 'var(--color-on-surface-variant)',
                    border: 'none',
                    borderRadius: 'var(--radius-sm)',
                    fontWeight: '700',
                    cursor: 'pointer'
                  }}
                  data-testid="reject-mod-btn"
                >
                  {loadingReject ? '處理中...' : '拒絕此變更請求'}
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
      )}
    </div>
  );
};

export default SitterModificationQuote;
