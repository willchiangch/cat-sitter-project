import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../../components/ui/Card';
import {
  confirmModification,
  confirmRefund,
  getActiveModificationRequest
} from '../../api/orderApi';
import type { ModificationRequestDetailDto } from '../../api/orderApi';
import { useRole } from '../../contexts/RoleContext';
import { formatDiff, resolveModificationPhase } from '../../components/orders/modificationPhase';
import RefundProofImage from '../../components/orders/RefundProofImage';

interface OwnerModificationConfirmProps {
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

const OwnerModificationConfirm: React.FC<OwnerModificationConfirmProps> = ({ orderId }) => {
  const { currentRole } = useRole();
  const navigate = useNavigate();
  const [modRequest, setModRequest] = useState<ModificationRequestDetailDto | null>(null);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [agreedDiffAmount, setAgreedDiffAmount] = useState<number>(0);
  const [loadingConfirm, setLoadingConfirm] = useState(false);
  const [loadingRefund, setLoadingRefund] = useState(false);
  const [refundConfirmed, setRefundConfirmed] = useState(false);
  const confirmKeyRef = useRef<string | null>(null);

  // 每次動作成功後重新讀取，畫面一律依後端最新狀態收斂
  const loadModRequest = useCallback(
    () =>
      getActiveModificationRequest(orderId)
        .then((detail) => {
          setModRequest(detail);
          if (detail) setAgreedDiffAmount(detail.diffAmount);
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

  const handleConfirmMod = async (e: React.FormEvent) => {
    e.preventDefault();
    if (currentRole !== 'client') {
      alert('無飼主權限，請先切換至飼主角色！');
      return;
    }
    if (!modRequest) return;

    // 零信任對帳防線：後端會再次核對此金額與保母最新報價是否一致
    if (agreedDiffAmount !== modRequest.diffAmount) {
      alert('⚠️ 零信任對帳警告：您同意的差額與系統顯示的變更差額不符，已阻擋交易！');
      return;
    }

    setLoadingConfirm(true);
    try {
      if (!confirmKeyRef.current) {
        confirmKeyRef.current = crypto.randomUUID();
      }
      await confirmModification(
        orderId,
        modRequest.id,
        { agreedDiffAmount, version: modRequest.orderVersion },
        confirmKeyRef.current
      );

      confirmKeyRef.current = null;
      alert('變更確認成功！');
      await loadModRequest();
    } catch (err) {
      console.error(err);
      confirmKeyRef.current = null;
      alert('變更確認失敗，請重試。');
    } finally {
      setLoadingConfirm(false);
    }
  };

  const handleConfirmRefund = async () => {
    if (currentRole !== 'client') {
      alert('無飼主權限，請先切換至飼主角色！');
      return;
    }
    setLoadingRefund(true);
    try {
      await confirmRefund(orderId);
      // 確認後訂單已回到原狀態、查不到進行中的變更，不重新讀取，直接顯示終態
      setRefundConfirmed(true);
      alert('已確認收到退款，訂單變更正式生效！');
    } catch (err) {
      console.error(err);
      alert('確認退款失敗，請重試。');
    } finally {
      setLoadingRefund(false);
    }
  };

  const phase = modRequest ? resolveModificationPhase(modRequest) : null;
  const ownerAgreed = phase === 'SUPPLEMENT' || phase === 'REFUND';

  const renderConfirmCardBody = () => {
    if (!modRequest) return null;
    if (phase === 'AWAIT_QUOTE') {
      return (
        <div style={mutedNoticeStyle} data-testid="mod-confirm-awaiting-quote">
          等待保母審核並報價，報價後即可在此確認。
        </div>
      );
    }
    if (phase === 'AWAIT_OWNER_CONFIRM') {
      return (
        <form
          onSubmit={handleConfirmMod}
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: '1.25rem',
            marginTop: '1.25rem'
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem' }}>
            <label style={{ fontSize: '0.875rem', fontWeight: '600' }}>
              請輸入您同意的變更差額 (以進行防線核對)
            </label>
            <input
              type="number"
              value={agreedDiffAmount}
              onChange={(e) => setAgreedDiffAmount(Number(e.target.value))}
              style={{
                padding: '0.75rem',
                borderRadius: 'var(--radius-sm)',
                border: 'none',
                backgroundColor: 'var(--color-surface-low)',
                color: 'var(--color-on-surface)'
              }}
              data-testid="agreed-diff-input"
              required
            />
          </div>

          <button
            type="submit"
            disabled={loadingConfirm || currentRole !== 'client'}
            className="btn-primary"
            style={{ width: '100%', padding: '1rem' }}
            data-testid="confirm-submit-btn"
          >
            {loadingConfirm ? '確認中...' : '同意變更並提交'}
          </button>
        </form>
      );
    }
    if (ownerAgreed) {
      return (
        <div>
          <div style={noticeStyle} data-testid="confirm-success-banner">
            ✓ 變更已確認（{formatDiff(modRequest.diffAmount)}）
          </div>
          {phase === 'SUPPLEMENT' && (
            <div
              style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}
              data-testid="mod-confirm-supplement-notice"
            >
              <p
                style={{
                  fontSize: '0.875rem',
                  color: 'var(--color-on-surface-variant)',
                  margin: 0,
                  textAlign: 'center'
                }}
              >
                請回到訂單詳情匯款並上傳補款憑證，保母核對入帳後變更正式生效。
              </p>
              <button
                type="button"
                onClick={() => navigate(`/owner/orders/${orderId}`)}
                className="btn-primary"
                style={{ width: '100%', padding: '1rem' }}
                data-testid="mod-confirm-go-pay-btn"
              >
                前往補款
              </button>
            </div>
          )}
        </div>
      );
    }
    return (
      <div style={mutedNoticeStyle} data-testid="mod-confirm-readonly-summary">
        此變更請求已結束
      </div>
    );
  };

  const renderRefundCardBody = () => {
    if (!modRequest) return null;
    if (refundConfirmed) {
      return (
        <div style={noticeStyle} data-testid="refund-confirm-success-banner">
          ✓ 已確認收到退款
        </div>
      );
    }
    if (!modRequest.hasRefundProof) {
      return (
        <div style={mutedNoticeStyle} data-testid="refund-awaiting-proof">
          等待保母上傳退款轉帳憑證，上傳後即可在此確認收到退款。
        </div>
      );
    }
    return (
      <>
        <p
          style={{
            fontSize: '0.875rem',
            color: 'var(--color-on-surface-variant)',
            margin: '0 0 1.5rem 0'
          }}
        >
          保母已上傳退款轉帳收據，請在確認您實體帳戶收到退款後點擊此按鈕，正式完成訂單變更結轉。
        </p>
        <RefundProofImage
          orderId={orderId}
          alt="保母上傳的退款轉帳憑證"
          style={{ width: '100%', borderRadius: 'var(--radius-sm)', marginBottom: '1.5rem' }}
        />
        <button
          onClick={handleConfirmRefund}
          disabled={loadingRefund || currentRole !== 'client'}
          className="btn-primary"
          style={{ width: '100%', padding: '1rem' }}
          data-testid="refund-confirm-btn"
        >
          {loadingRefund ? '確認中...' : '確認已收到退款'}
        </button>
      </>
    );
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
        飼主變更與退款確認
      </h2>

      {currentRole !== 'client' && (
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
          ⚠️ 警告：當前角色非飼主，無法調用確認變更或退款 API。請先在 Demo 首頁切換為飼主。
        </div>
      )}

      {fetchLoading ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#9ca3af' }}>載入中...</div>
      ) : fetchError ? (
        <div
          style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }}
          data-testid="mod-confirm-error"
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
          data-testid="mod-confirm-empty"
        >
          目前沒有進行中的變更協商：先前的協商已結清或已拒絕，或這筆訂單尚未發起變更。
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
          <Card>
            <h3
              style={{
                margin: '0 0 1rem 0',
                fontSize: '1.25rem',
                fontFamily: 'var(--font-display)'
              }}
            >
              變更方案確認
            </h3>
            {phase !== 'AWAIT_QUOTE' && (
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  padding: '1rem 0',
                  borderBottom: '1px solid var(--color-surface-high)',
                  fontSize: '0.875rem'
                }}
              >
                <span>保母報價差額：</span>
                <strong
                  style={{ color: modRequest.diffAmount < 0 ? '#dc2626' : '#16a34a' }}
                  data-testid="mod-confirm-diff-amount"
                >
                  $ {modRequest.diffAmount.toLocaleString()} (負值為退款)
                </strong>
              </div>
            )}

            {renderConfirmCardBody()}
          </Card>

          {/* 退款區塊只屬於「已同意、差額為負」的 REFUND 階段；補款分支、協商中都不顯示 */}
          {phase === 'REFUND' && (
            <Card data-testid="refund-confirm-section">
              <h3
                style={{
                  margin: '0 0 1rem 0',
                  fontSize: '1.25rem',
                  fontFamily: 'var(--font-display)'
                }}
              >
                確認收到退款 (線下退款核對)
              </h3>
              {renderRefundCardBody()}
            </Card>
          )}
        </div>
      )}
    </div>
  );
};

export default OwnerModificationConfirm;
