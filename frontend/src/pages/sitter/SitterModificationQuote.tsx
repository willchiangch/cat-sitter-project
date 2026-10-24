import React, { useCallback, useEffect, useRef, useState } from 'react';
import Card from '../../components/ui/Card';
import {
  getActiveModificationRequest,
  quoteModification,
  rejectModification,
  uploadRefundProof
} from '../../api/orderApi';
import type { ModificationRequestDetailDto } from '../../api/orderApi';
import { useRole } from '../../contexts/RoleContext';
import { formatDiff, resolveModificationPhase } from '../../components/orders/modificationPhase';
import RefundProofImage from '../../components/orders/RefundProofImage';
import { formatMoney } from '../../utils/money';

interface SitterModificationQuoteProps {
  orderId: string;
}

const noticeStyle: React.CSSProperties = {
  textAlign: 'center',
  padding: '1rem',
  color: 'var(--color-primary)',
  fontWeight: '600'
};

const mutedNoticeStyle: React.CSSProperties = {
  textAlign: 'center',
  padding: '1rem',
  color: 'var(--color-on-surface-variant)',
  fontWeight: '600'
};

const SitterModificationQuote: React.FC<SitterModificationQuoteProps> = ({ orderId }) => {
  const { currentRole } = useRole();
  const [modRequest, setModRequest] = useState<ModificationRequestDetailDto | null>(null);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [newTotalAmount, setNewTotalAmount] = useState<number>(0);
  const [refundProofFile, setRefundProofFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadingReject, setLoadingReject] = useState(false);
  const [loadingProof, setLoadingProof] = useState(false);
  const [rejected, setRejected] = useState(false);
  const [proofSent, setProofSent] = useState(false);
  const quoteKeyRef = useRef<string | null>(null);
  const rejectKeyRef = useRef<string | null>(null);

  // 每次動作成功後重新讀取，畫面一律依後端最新狀態收斂（不靠本地旗標猜測下一步）
  const loadModRequest = useCallback(
    () =>
      getActiveModificationRequest(orderId)
        .then((detail) => {
          setModRequest(detail);
          if (detail) setNewTotalAmount(detail.newTotalAmount);
          setFetchError(null);
        })
        .catch((err) => {
          // 「沒有進行中的協商」後端回 204（不會進 catch）；這裡只剩真正的錯誤（權限、網路、找不到訂單）
          console.error(err);
          setFetchError(err.response?.data?.message || '讀取變更協商失敗，請稍後再試。');
        }),
    [orderId]
  );

  useEffect(() => {
    loadModRequest().finally(() => setFetchLoading(false));
  }, [loadModRequest]);

  const handleSendQuote = async (e: React.FormEvent) => {
    e.preventDefault();
    if (currentRole !== 'sitter') {
      alert('無保母權限，請先切換至保母角色！');
      return;
    }
    if (!modRequest) return;

    setLoading(true);
    try {
      if (!quoteKeyRef.current) {
        quoteKeyRef.current = crypto.randomUUID();
      }
      await quoteModification(
        orderId,
        modRequest.id,
        { newTotalAmount, version: modRequest.orderVersion },
        quoteKeyRef.current
      );
      quoteKeyRef.current = null;
      alert('報價已成功送出，等待飼主確認！');
      await loadModRequest();
    } catch (err) {
      console.error(err);
      quoteKeyRef.current = null;
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
      if (!rejectKeyRef.current) {
        rejectKeyRef.current = crypto.randomUUID();
      }
      await rejectModification(orderId, modRequest.id, rejectKeyRef.current);
      rejectKeyRef.current = null;
      // 拒絕後訂單已回到原狀態、查不到進行中的變更，不重新讀取，直接顯示終態
      setRejected(true);
      alert('已拒絕此變更請求，訂單已恢復原狀態！');
    } catch (err) {
      console.error(err);
      rejectKeyRef.current = null;
      alert('拒絕變更失敗，請稍後再試。');
    } finally {
      setLoadingReject(false);
    }
  };

  const handleUploadProof = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!refundProofFile) {
      alert('請選擇退款轉帳憑證圖片');
      return;
    }
    setLoadingProof(true);
    try {
      await uploadRefundProof(orderId, refundProofFile);
      setProofSent(true);
      alert('退款憑證已成功上傳！');
      await loadModRequest();
    } catch (err) {
      console.error(err);
      alert('憑證上傳失敗，請檢查參數。');
    } finally {
      setLoadingProof(false);
    }
  };

  const phase = modRequest ? resolveModificationPhase(modRequest) : null;
  const refundProofUploaded = proofSent || !!modRequest?.hasRefundProof;

  const renderQuoteCardBody = () => {
    if (!modRequest) return null;
    if (rejected) {
      return (
        <div style={mutedNoticeStyle} data-testid="reject-success-banner">
          ✓ 已拒絕此變更請求
        </div>
      );
    }
    if (phase === 'AWAIT_QUOTE') {
      return (
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

          {renderRejectButton()}
        </form>
      );
    }
    if (phase === 'AWAIT_OWNER_CONFIRM') {
      return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>
          <div style={noticeStyle} data-testid="quote-success-banner">
            ✓ 報價已送出 (總額: {formatMoney(modRequest.newTotalAmount)})，等待飼主確認
          </div>
          {renderRejectButton()}
        </div>
      );
    }
    // 飼主已同意 (SUPPLEMENT / REFUND) 或已結束：唯讀摘要，不再提供報價/拒絕
    return (
      <div style={mutedNoticeStyle} data-testid="mod-quote-readonly-summary">
        {phase === 'CLOSED'
          ? '此變更請求已結束'
          : `飼主已同意變更：新總額 ${formatMoney(modRequest.newTotalAmount)}（${formatDiff(modRequest.diffAmount)}）`}
        {phase === 'SUPPLEMENT' && (
          <div style={{ fontWeight: 400, marginTop: '0.5rem', fontSize: '0.875rem' }}>
            等待飼主補款，收到補款憑證後請至訂單列表核對入帳。
          </div>
        )}
      </div>
    );
  };

  const renderRejectButton = () => (
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
  );

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
      ) : fetchError ? (
        <div
          style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}
          data-testid="mod-quote-error"
        >
          {fetchError}
        </div>
      ) : !modRequest ? (
        // 協商已結清 / 已拒絕 / 從未發起是正常終態，用中性資訊樣式，不用錯誤紅字（驗收 N8）
        <div
          style={{
            textAlign: 'center',
            padding: '40px 1rem',
            color: 'var(--color-on-surface-variant)'
          }}
          data-testid="mod-quote-empty"
        >
          目前沒有進行中的變更協商：先前的協商已結清或已拒絕，或這筆訂單尚未發起變更。
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
                {formatMoney(modRequest.currentOrderTotalAmount)}
              </strong>
            </div>

            {renderQuoteCardBody()}
          </Card>

          {/* 退款憑證只屬於「飼主已同意、差額為負」這一個階段；補款分支與協商中都不顯示 */}
          {phase === 'REFUND' && !rejected && (
            <Card data-testid="refund-proof-section">
              <h3
                style={{
                  margin: '0 0 1.5rem 0',
                  fontSize: '1.25rem',
                  fontFamily: 'var(--font-display)'
                }}
              >
                退款轉帳憑證上傳（{formatDiff(modRequest.diffAmount)}）
              </h3>
              {refundProofUploaded ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
                  <div style={noticeStyle} data-testid="refund-proof-success-banner">
                    ✓ 退款憑證已成功上傳，等待飼主確認收到退款
                  </div>
                  <RefundProofImage
                    orderId={orderId}
                    alt="退款轉帳憑證"
                    style={{ width: '100%', borderRadius: 'var(--radius-sm)' }}
                  />
                </div>
              ) : (
                <form
                  onSubmit={handleUploadProof}
                  style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}
                >
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
                    <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>
                      退款轉帳憑證截圖（JPG / PNG / WEBP，5MB 以內）
                    </label>
                    <input
                      type="file"
                      accept="image/jpeg,image/png,image/webp"
                      onChange={(e) => setRefundProofFile(e.target.files?.[0] ?? null)}
                      data-testid="refund-proof-file-input"
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
          )}
        </div>
      )}
    </div>
  );
};

export default SitterModificationQuote;
