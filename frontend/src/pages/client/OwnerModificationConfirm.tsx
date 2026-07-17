import React, { useEffect, useState } from 'react';
import Card from '../../components/ui/Card';
import {
  confirmModification,
  confirmRefund,
  getActiveModificationRequest
} from '../../api/orderApi';
import type { ModificationRequestDetailDto } from '../../api/orderApi';
import { useRole } from '../../contexts/RoleContext';
import { useCurrentUser } from '../../hooks/useCurrentUser';

interface OwnerModificationConfirmProps {
  orderId: string;
}

const OwnerModificationConfirm: React.FC<OwnerModificationConfirmProps> = ({ orderId }) => {
  const { currentRole } = useRole();
  const { userId: ownerId } = useCurrentUser();
  const [modRequest, setModRequest] = useState<ModificationRequestDetailDto | null>(null);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [agreedDiffAmount, setAgreedDiffAmount] = useState<number>(0);
  const [loadingConfirm, setLoadingConfirm] = useState(false);
  const [loadingRefund, setLoadingRefund] = useState(false);
  const [confirmStatus, setConfirmStatus] = useState<'IDLE' | 'SUCCESS' | 'ERROR'>('IDLE');
  const [refundStatus, setRefundStatus] = useState<'IDLE' | 'SUCCESS' | 'ERROR'>('IDLE');

  useEffect(() => {
    getActiveModificationRequest(orderId)
      .then((detail) => {
        setModRequest(detail);
        setAgreedDiffAmount(detail.diffAmount);
      })
      .catch((err) => {
        console.error(err);
        setFetchError('找不到進行中的變更請求，可能已被處理或尚未經保母報價。');
      })
      .finally(() => setFetchLoading(false));
  }, [orderId]);

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
      const idempotencyKey = crypto.randomUUID();
      await confirmModification(
        orderId,
        modRequest.id,
        { agreedDiffAmount, version: modRequest.orderVersion },
        idempotencyKey
      );

      setConfirmStatus('SUCCESS');
      alert('變更確認成功！');
    } catch (err) {
      console.error(err);
      setConfirmStatus('ERROR');
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
      await confirmRefund(orderId, ownerId);
      setRefundStatus('SUCCESS');
      alert('已確認收到退款，訂單變更正式生效！');
    } catch (err) {
      console.error(err);
      setRefundStatus('ERROR');
      alert('確認退款失敗，請重試。');
    } finally {
      setLoadingRefund(false);
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
      ) : fetchError || !modRequest ? (
        <div style={{ textAlign: 'center', padding: '40px 0', color: '#dc2626' }} data-testid="mod-confirm-error">
          {fetchError}
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
          <Card>
            <h3
              style={{ margin: '0 0 1rem 0', fontSize: '1.25rem', fontFamily: 'var(--font-display)' }}
            >
              變更方案確認
            </h3>
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

            {confirmStatus === 'SUCCESS' ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '1rem',
                  color: 'var(--color-primary)',
                  fontWeight: '600'
                }}
                data-testid="confirm-success-banner"
              >
                ✓ 變更已確認
              </div>
            ) : (
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
            )}
          </Card>

          <Card>
            <h3
              style={{ margin: '0 0 1rem 0', fontSize: '1.25rem', fontFamily: 'var(--font-display)' }}
            >
              確認收到退款 (線下退款核對)
            </h3>
            <p
              style={{
                fontSize: '0.875rem',
                color: 'var(--color-on-surface-variant)',
                margin: '0 0 1.5rem 0'
              }}
            >
              若此變更涉及退款，且保母已上傳退款轉帳收據，請在確認您實體帳戶收到退款後點擊此按鈕，正式完成訂單變更結轉。
            </p>

            {refundStatus === 'SUCCESS' ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '1rem',
                  color: 'var(--color-primary)',
                  fontWeight: '600'
                }}
                data-testid="refund-confirm-success-banner"
              >
                ✓ 已確認收到退款
              </div>
            ) : (
              <button
                onClick={handleConfirmRefund}
                disabled={loadingRefund || currentRole !== 'client'}
                className="btn-primary"
                style={{ width: '100%', padding: '1rem' }}
                data-testid="refund-confirm-btn"
              >
                {loadingRefund ? '確認中...' : '確認已收到退款'}
              </button>
            )}
          </Card>
        </div>
      )}
    </div>
  );
};

export default OwnerModificationConfirm;
