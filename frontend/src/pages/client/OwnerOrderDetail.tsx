import React, { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import Card from '../../components/ui/Card';
import StatusBadge from '../../components/ui/StatusBadge';
import { ownerVisitActionLabel, visitStatusLabel } from '../../components/ui/statusLabels';
import OrderDisputeModal from '../../components/orders/OrderDisputeModal';
import PaymentProofImage from '../../components/orders/PaymentProofImage';
import {
  cancelOrderByOwner,
  completeOrder,
  disputeOrder,
  getOrderDetail,
  submitPaymentProof
} from '../../api/orderApi';
import type { OrderDetailResponseDto } from '../../api/orderApi';
import { useCurrentUser } from '../../hooks/useCurrentUser';
import { useOrderVisitsQuery } from '../../hooks/useOrders';
import { formatMoney } from '../../utils/money';

interface OwnerOrderDetailProps {
  orderId: string;
}

// 訂單成立後的行程清單唯讀檢視，串接日誌檢視頁面 (SD-008)。訂單詳情本身不帶 visitId，
// 只能靠 GET /api/orders/{orderId}/visits 另外查
const OwnerOrderVisitList: React.FC<{ orderId: string }> = ({ orderId }) => {
  const navigate = useNavigate();
  const { data: visits = [], isLoading } = useOrderVisitsQuery(orderId, true);

  if (isLoading || visits.length === 0) return null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '0.5rem', marginBottom: '1rem' }}>
      <h4 style={{ margin: '0 0 0.25rem 0' }}>行程</h4>
      {visits.map((visit) => {
        // 只有已完工的行程才可能有已送出的日誌；待執行 / 執行中點進去只會是空日誌（驗收 N11）
        const action = ownerVisitActionLabel(visit.status);
        return (
          <div
            key={visit.id}
            data-testid="owner-order-visit-item"
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              padding: '0.5rem 0.75rem',
              backgroundColor: 'var(--color-surface-low)',
              borderRadius: '8px',
              fontSize: '0.875rem'
            }}
          >
            <span>
              {new Date(visit.scheduledAt).toLocaleDateString()}（{visitStatusLabel(visit.status)}）
            </span>
            {action && (
              <button
                type="button"
                onClick={() => navigate(`/visit-reports/view/${visit.id}`)}
                className="btn-primary"
                data-testid="owner-order-visit-link"
                style={{ padding: '4px 12px', fontSize: '0.8rem', flexShrink: 0 }}
              >
                {action}
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
};

/** 行程已結束（打卡完工或逾期被系統關閉），與後端 CompletionService.manualComplete 的判斷一致 */
const FINISHED_VISIT_STATUSES = new Set(['DONE', 'CLOSED_BY_SYSTEM']);

const generateUUID = () => {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0,
      v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
};

const OwnerOrderDetail: React.FC<OwnerOrderDetailProps> = ({ orderId }) => {
  const navigate = useNavigate();
  const [order, setOrder] = useState<OrderDetailResponseDto | null>(null);
  const [isDisputeOpen, setIsDisputeOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 飼主付款憑證表單 State
  const [lastFive, setLastFive] = useState('');
  const [disclaimerAgreed, setDisclaimerAgreed] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitLoading, setSubmitLoading] = useState(false);
  const paymentProofKeyRef = useRef<string | null>(null);

  const { userId: ownerId } = useCurrentUser();

  // 確認結案按鈕只在「所有行程都已結束」時顯示（PRD-009 A.1 / 例外表「仍有行程尚未完成」）。
  // 過去 CONFIRMED（服務還沒開始）就顯示，按了只會被後端 manualComplete 擋下（驗收 N10）
  const isServiceStage = order?.status === 'CONFIRMED' || order?.status === 'IN_PROGRESS';
  const { data: visits = [], isSuccess: visitsLoaded } = useOrderVisitsQuery(
    orderId,
    isServiceStage
  );
  const canComplete =
    visitsLoaded && visits.length > 0 && visits.every((v) => FINISHED_VISIT_STATUSES.has(v.status));

  const fetchOrderDetail = async () => {
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
    fetchOrderDetail();
  }, [orderId]);

  const handleComplete = async () => {
    if (!window.confirm('確定要將此訂單結案嗎？這將會放行款項給保母。')) return;
    setLoading(true);
    try {
      await completeOrder(orderId, ownerId);
      await fetchOrderDetail();
      alert('訂單已順利結案！');
    } catch (err) {
      console.error(err);
      alert('結案失敗，請稍後再試。');
    } finally {
      setLoading(false);
    }
  };

  // 飼主付款前撤單（PRD-006 例外處理）。可不可撤由後端 cancellableByOwner 決定（含補款階段排除），前端不自行推論
  const handleCancelBooking = async () => {
    if (!order) return;
    if (!window.confirm('確定要取消這筆預約嗎？取消後保母會收到通知，此動作無法復原。')) return;
    setLoading(true);
    try {
      await cancelOrderByOwner(orderId, order.version ?? 0);
      await fetchOrderDetail();
      alert('已取消預約');
    } catch (err: any) {
      console.error(err);
      alert(err.response?.data?.message || '取消預約失敗，請稍後再試');
      // 409（保母剛接單 / 報價、已上傳憑證）時重抓，讓畫面反映最新狀態
      await fetchOrderDetail();
    } finally {
      setLoading(false);
    }
  };

  const handleDisputeSubmit = async (category: string, description: string) => {
    try {
      await disputeOrder(orderId, ownerId, category, description);
      await fetchOrderDetail();
      alert('爭議申報成功，請靜候管理員處理。');
    } catch (err) {
      console.error(err);
      alert('申報失敗，請稍後再試。');
      throw err;
    }
  };

  const handlePaymentProofSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitError(null);

    if (!disclaimerAgreed) {
      setSubmitError('必須勾選並同意線下交易免責聲明');
      return;
    }
    if (!/^\d{5}$/.test(lastFive)) {
      setSubmitError('轉帳帳號後五碼必須為 5 位數字');
      return;
    }
    if (!file) {
      setSubmitError('請上傳轉帳憑證圖片檔案');
      return;
    }

    setSubmitLoading(true);
    try {
      if (!paymentProofKeyRef.current) {
        paymentProofKeyRef.current = generateUUID();
      }
      await submitPaymentProof(
        orderId,
        lastFive,
        disclaimerAgreed,
        file,
        paymentProofKeyRef.current
      );
      paymentProofKeyRef.current = null;
      await fetchOrderDetail();
      alert('付款憑證提交成功，等待保母確認！');
    } catch (err: any) {
      console.error(err);
      paymentProofKeyRef.current = null;
      const msg = err.response?.data?.message || '憑證提交失敗，請檢查格式或網路';
      setSubmitError(msg);
    } finally {
      setSubmitLoading(false);
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
      <div style={{ padding: '2rem', textAlign: 'center', color: '#ef4444' }}>
        {error || '找不到訂單詳情'}
      </div>
    );
  }

  const { status, totalAmount, sitterPaymentInfo, hasPaymentProof, paymentProofLastFive } = order;
  const isPaymentStage = status === 'PENDING_PAYMENT' || status === 'PAID';
  const amountDue = order.amountDue ?? totalAmount;
  // 訂單變更補款：totalAmount 已是變更後新總額，飼主只需匯差額 (PRD-016 主流程 C.3)
  const isTopUp = isPaymentStage && amountDue !== totalAmount;

  return (
    <div
      style={{
        padding: '2rem 1.5rem',
        fontFamily: 'var(--font-sans)',
        maxWidth: '600px',
        margin: '0 auto'
      }}
    >
      <button
        onClick={() => navigate('/owner/orders')}
        style={{
          border: 'none',
          background: 'none',
          color: 'var(--color-primary)',
          fontSize: '0.875rem',
          fontWeight: '600',
          cursor: 'pointer',
          padding: 0,
          marginBottom: '1.5rem',
          display: 'flex',
          alignItems: 'center',
          gap: '4px'
        }}
      >
        ← 返回訂單列表
      </button>

      <Card>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            marginBottom: '1.5rem',
            alignItems: 'center'
          }}
        >
          <span
            style={{
              fontSize: '0.75rem',
              fontWeight: '500',
              color: 'var(--color-on-surface-variant)',
              letterSpacing: '0.05em'
            }}
          >
            ORDER #{orderId.slice(0, 8)}
          </span>
          <StatusBadge status={status} />
        </div>

        <div
          style={{
            borderBottom: '1px solid var(--color-surface-high)',
            paddingBottom: '1.5rem',
            marginBottom: '1.5rem'
          }}
        >
          <h3
            style={{
              margin: '0 0 0.5rem 0',
              fontSize: '1.25rem',
              fontWeight: '700',
              color: 'var(--color-on-surface)'
            }}
          >
            服務內容
          </h3>
          <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--color-on-surface-variant)' }}>
            保母 ID: {order.sitterId}
          </p>
        </div>

        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: '2rem'
          }}
        >
          <span style={{ fontSize: '1rem', fontWeight: '600', color: 'var(--color-on-surface)' }}>
            {isTopUp ? '變更後訂單總額' : '訂單金額'}
          </span>
          <span
            data-testid="order-total-amount"
            style={{
              fontSize: isTopUp ? '1rem' : '1.5rem',
              fontWeight: isTopUp ? '600' : '800',
              color: isTopUp ? 'var(--color-on-surface-variant)' : 'var(--color-primary)'
            }}
          >
            {formatMoney(totalAmount)}
          </span>
        </div>

        {/* 訂單變更補款：顯示本次應補差額，避免飼主照新總額整筆匯款 */}
        {isTopUp && (
          <div
            data-testid="payment-top-up-notice"
            style={{
              backgroundColor: '#fffbeb',
              border: '1px solid #fde68a',
              borderRadius: '8px',
              padding: '1rem 1.25rem',
              marginBottom: '2rem'
            }}
          >
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center'
              }}
            >
              <span style={{ fontSize: '1rem', fontWeight: '700', color: '#92400e' }}>
                本次應補差額
              </span>
              <span
                data-testid="payment-amount-due"
                style={{ fontSize: '1.5rem', fontWeight: '800', color: 'var(--color-primary)' }}
              >
                {formatMoney(amountDue)}
              </span>
            </div>
            <p style={{ margin: '0.5rem 0 0 0', fontSize: '0.8125rem', color: '#92400e' }}>
              訂單已依變更協商調整為 {formatMoney(totalAmount)}，原訂單款項已付清， 本次只需匯入差額{' '}
              {formatMoney(amountDue)}。
            </p>
          </div>
        )}

        {/* 轉帳帳戶資訊區塊 (僅在 PENDING_PAYMENT 與 PAID 狀態且 sitterPaymentInfo 存在時顯示) */}
        {(status === 'PENDING_PAYMENT' || status === 'PAID') &&
          sitterPaymentInfo &&
          sitterPaymentInfo.bankAccount && (
            <div
              data-testid="bank-info-container"
              style={{
                backgroundColor: 'var(--color-surface-low)',
                padding: '1.25rem',
                borderRadius: '8px',
                border: '1px solid var(--color-surface-high)',
                marginBottom: '2rem'
              }}
            >
              <h4 style={{ margin: '0 0 0.75rem 0', color: 'var(--color-on-surface)' }}>
                保母收款帳戶
              </h4>
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '0.5rem',
                  fontSize: '0.875rem'
                }}
              >
                <div>
                  <span style={{ color: 'var(--color-on-surface-variant)' }}>銀行代碼：</span>
                  <strong data-testid="bank-code-text">{sitterPaymentInfo.bankCode}</strong>
                </div>
                <div>
                  <span style={{ color: 'var(--color-on-surface-variant)' }}>分行名稱：</span>
                  <strong>{sitterPaymentInfo.bankBranch}</strong>
                </div>
                <div>
                  <span style={{ color: 'var(--color-on-surface-variant)' }}>銀行帳號：</span>
                  <strong data-testid="bank-account-text">{sitterPaymentInfo.bankAccount}</strong>
                </div>
                <div>
                  <span style={{ color: 'var(--color-on-surface-variant)' }}>戶名：</span>
                  <strong data-testid="bank-payee-text">{sitterPaymentInfo.bankPayeeName}</strong>
                </div>
              </div>
            </div>
          )}

        {/* 憑證上傳區塊 (PENDING_PAYMENT) */}
        {status === 'PENDING_PAYMENT' && (
          <form
            onSubmit={handlePaymentProofSubmit}
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: '1.25rem',
              marginBottom: '2rem'
            }}
          >
            <h4 style={{ margin: 0, color: 'var(--color-on-surface)' }}>提交線下付款憑證</h4>

            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.875rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem'
                }}
              >
                轉帳帳號後五碼
              </label>
              <input
                type="text"
                maxLength={5}
                value={lastFive}
                onChange={(e) => setLastFive(e.target.value)}
                placeholder="例如 12345"
                style={{
                  width: '100%',
                  padding: '0.75rem',
                  borderRadius: '8px',
                  border: '1px solid var(--color-surface-high)',
                  backgroundColor: 'var(--color-surface)',
                  color: 'var(--color-on-surface)'
                }}
                data-testid="input-payment-last-five"
              />
            </div>

            <div>
              <label
                style={{
                  display: 'block',
                  fontSize: '0.875rem',
                  fontWeight: '600',
                  marginBottom: '0.5rem'
                }}
              >
                上傳憑證照片
              </label>
              <input
                type="file"
                accept="image/*"
                onChange={(e) => setFile(e.target.files ? e.target.files[0] : null)}
                style={{
                  width: '100%',
                  padding: '0.5rem 0'
                }}
                data-testid="input-payment-file"
              />
            </div>

            <div style={{ display: 'flex', alignItems: 'flex-start', gap: '0.5rem' }}>
              <input
                type="checkbox"
                id="disclaimer"
                checked={disclaimerAgreed}
                onChange={(e) => setDisclaimerAgreed(e.target.checked)}
                style={{ marginTop: '3px' }}
                data-testid="checkbox-disclaimer-agreed"
              />
              <label
                htmlFor="disclaimer"
                style={{
                  fontSize: '0.8125rem',
                  color: 'var(--color-on-surface-variant)',
                  lineHeight: '1.4'
                }}
              >
                我已確認轉帳完成。本人同意此筆交易係線下自行轉帳，若因帳號填寫錯誤或憑證造假導致任何款項爭議，需由本人自行承擔相關責任。
              </label>
            </div>

            {submitError && (
              <div style={{ color: '#ef4444', fontSize: '0.875rem', fontWeight: '500' }}>
                {submitError}
              </div>
            )}

            <button
              type="submit"
              disabled={submitLoading}
              className="btn-primary"
              style={{ width: '100%', padding: '0.875rem' }}
              data-testid="btn-submit-payment-proof"
            >
              {submitLoading ? '提交中...' : '確認提交付款憑證'}
            </button>
          </form>
        )}

        {/* 已付款待核對提示區 (PAID) */}
        {status === 'PAID' && (
          <div
            style={{
              backgroundColor: '#eff6ff',
              color: '#1e40af',
              padding: '1.25rem',
              borderRadius: '8px',
              border: '1px solid #bfdbfe',
              marginBottom: '2rem'
            }}
          >
            <h4 style={{ margin: '0 0 0.5rem 0' }}>✓ 已提交付款憑證</h4>
            <p style={{ margin: '0 0 1rem 0', fontSize: '0.875rem' }}>
              您的付款資訊已送出，目前正等待保母核對入帳。
            </p>
            <div
              style={{
                fontSize: '0.875rem',
                display: 'flex',
                flexDirection: 'column',
                gap: '0.25rem'
              }}
            >
              <div>
                轉帳後五碼：<strong>{paymentProofLastFive}</strong>
              </div>
              {hasPaymentProof && (
                <div style={{ marginTop: '0.75rem' }}>
                  <div
                    style={{ marginBottom: '0.25rem', color: 'var(--color-on-surface-variant)' }}
                  >
                    憑證預覽：
                  </div>
                  <PaymentProofImage
                    orderId={orderId}
                    alt="Payment Proof"
                    style={{
                      maxWidth: '100%',
                      maxHeight: '180px',
                      borderRadius: '4px',
                      border: '1px solid #bfdbfe'
                    }}
                  />
                </div>
              )}
            </div>
          </div>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {order.cancellableByOwner && (
            <button
              onClick={handleCancelBooking}
              disabled={loading}
              className="btn-primary"
              style={{
                width: '100%',
                padding: '1rem',
                background: 'var(--color-surface-high)',
                color: 'var(--color-on-surface-variant)',
                boxShadow: 'none'
              }}
              data-testid="owner-cancel-booking-btn"
            >
              {loading ? '處理中...' : '取消預約'}
            </button>
          )}

          {status === 'CANCELLED' && (
            <div
              style={{
                textAlign: 'center',
                padding: '1rem',
                backgroundColor: 'var(--color-surface-high)',
                color: 'var(--color-on-surface-variant)',
                borderRadius: 'var(--radius-sm)',
                fontSize: '0.875rem',
                fontWeight: '600'
              }}
              data-testid="cancelled-status-banner"
            >
              {order.cancelledBy === 'OWNER' ? '您已取消此預約' : '此預約已取消'}
            </div>
          )}

          {(status === 'CONFIRMED' || status === 'IN_PROGRESS') && (
            <>
              <OwnerOrderVisitList orderId={orderId} />
              <button
                onClick={() => navigate(`/orders/${orderId}/modify`)}
                disabled={loading}
                className="btn-primary"
                // background 要用簡寫：只寫 backgroundColor 蓋不掉 .btn-primary 的漸層 background-image，
                // 過去灰字疊在藍色漸層上，對比度不到 AA 4.5:1（驗收 N6）
                style={{
                  width: '100%',
                  padding: '1rem',
                  background: 'var(--color-surface-high)',
                  color: 'var(--color-on-surface-variant)',
                  boxShadow: 'none'
                }}
                data-testid="btn-go-modification-wizard"
              >
                申請訂單變更 (改期 / 取消)
              </button>
              {canComplete ? (
                <button
                  onClick={handleComplete}
                  disabled={loading}
                  className="btn-primary"
                  style={{ width: '100%', padding: '1rem' }}
                  data-testid="complete-order-btn"
                >
                  {loading ? '處理中...' : '確認結案 (完成預約)'}
                </button>
              ) : (
                visitsLoaded && (
                  <p
                    data-testid="complete-order-hint"
                    style={{
                      margin: 0,
                      fontSize: '0.875rem',
                      color: 'var(--color-on-surface-variant)',
                      textAlign: 'center'
                    }}
                  >
                    所有行程完工後即可確認結案
                  </p>
                )
              )}
              <button
                onClick={() => setIsDisputeOpen(true)}
                disabled={loading}
                className="btn-primary"
                style={{
                  width: '100%',
                  padding: '1rem',
                  background: 'var(--color-surface-high)',
                  color: 'var(--color-on-surface-variant)',
                  boxShadow: 'none'
                }}
                data-testid="dispute-order-btn"
              >
                申報爭議
              </button>
            </>
          )}

          {(status === 'MODIFYING' || status === 'REFUND_VERIFY') && (
            <button
              onClick={() => navigate(`/owner/orders/${orderId}/modification-confirm`)}
              className="btn-primary"
              style={{ width: '100%', padding: '1rem' }}
              data-testid="btn-go-modification-confirm"
            >
              查看並確認訂單變更
            </button>
          )}

          {status === 'DISPUTED' && (
            <div
              style={{
                textAlign: 'center',
                padding: '1rem',
                backgroundColor: '#fef3c7',
                color: '#d97706',
                borderRadius: 'var(--radius-sm)',
                fontSize: '0.875rem',
                fontWeight: '600'
              }}
              data-testid="disputed-status-banner"
            >
              ⚠️ 訂單正處於爭議調解中，管理員將主動介入。
            </div>
          )}

          {status === 'COMPLETED' && (
            <div
              style={{
                textAlign: 'center',
                padding: '1rem',
                backgroundColor: '#dcfce7',
                color: '#16a34a',
                borderRadius: 'var(--radius-sm)',
                fontSize: '0.875rem',
                fontWeight: '600'
              }}
              data-testid="completed-status-banner"
            >
              ✓ 訂單已順利結案，感謝您的使用！
            </div>
          )}
        </div>
      </Card>

      <OrderDisputeModal
        isOpen={isDisputeOpen}
        onClose={() => setIsDisputeOpen(false)}
        onSubmit={handleDisputeSubmit}
      />
    </div>
  );
};

export default OwnerOrderDetail;
