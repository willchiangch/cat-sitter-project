import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import StatusBadge from '../../components/ui/StatusBadge';
import { INFO_BANNER_STYLE } from '../../components/ui/statusTone';
import { cancelReasonLabel } from '../../components/ui/statusLabels';
import PaymentSection from '../../components/orders/PaymentSection';
import {
  ModificationHistory,
  OrderGroupsView,
  OrderVisitList,
  QuoteBreakdown
} from '../../components/orders/OrderSections';
import {
  ConfirmSheet,
  CountedTextArea,
  ErrorText,
  KV,
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import { useInvalidateOrder, useOrderDetailQuery } from '../../hooks/useOrders';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { completeOrder, openServiceDispute, withdrawOrder } from '../../api/orderApi';
import type { OrderDetail } from '../../api/orderApi';
import { formatDateTime, isPast } from '../../utils/datetime';
import { codePointLength, stripText } from '../../utils/text';

/** 飼主取消結果標題（PRD-027 §9、PRD-026） */
const ownerCancelTitle = (reasonCode?: string): string => {
  switch (reasonCode) {
    case 'SITTER_DECLINED':
      return '保母已婉拒此預約';
    case 'SITTER_CANCELLED':
      return '保母已取消此訂單';
    case 'REVIEW_EXPIRED':
      return '保母未於期限內回覆，預約已自動取消';
    case 'OWNER_WITHDRAWN':
      return '您已取消此預約';
    default:
      return cancelReasonLabel(reasonCode);
  }
};

/** 結案提示（SD-FULFILL §9.2 completionHint 對照表） */
const completionHintText = (detail: OrderDetail): string | null => {
  const hint = detail.completionHint;
  if (!hint) return null;
  switch (hint.code) {
    case 'MODIFICATION_IN_PROGRESS':
      return '訂單變更處理中，完成後才能確認結案';
    case 'CARE_LOG_EXPIRED':
      return hint.autoCompleteAt && isPast(hint.autoCompleteAt, detail.serverTime)
        ? '即將自動結案'
        : `保母未於期限內送出照護日誌，訂單將於 ${formatDateTime(hint.autoCompleteAt)} 自動結案`;
    case 'VISITS_NOT_FINISHED':
    case 'CARE_LOG_PENDING':
    default:
      return '所有行程完成且保母送出照護日誌後，即可確認結案';
  }
};

const WithdrawBlock: React.FC<{ detail: OrderDetail; onDone: () => void }> = ({
  detail,
  onDone
}) => {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  const action = useIdempotentAction();
  const submit = async () => {
    const stripped = stripText(reason);
    const res = await action.run((key) =>
      withdrawOrder(
        detail.orderId,
        { expectedStatus: detail.status, ...(stripped ? { reason: stripped } : {}) },
        key
      )
    );
    if (res) {
      setOpen(false);
      onDone();
    }
  };
  return (
    <>
      <div className="btn-row">
        <Button
          variant="danger-outline"
          onClick={() => {
            action.reset();
            setOpen(true);
          }}
          data-testid="owner-order-detail-btn-withdraw"
        >
          撤回預約
        </Button>
      </div>
      <ConfirmSheet
        isOpen={open}
        title="撤回預約"
        message={
          <div className="stack">
            <span>撤回後保母將收到通知，此預約將取消。</span>
            <CountedTextArea
              id="withdraw-reason"
              label="撤回原因（選填）"
              value={reason}
              onChange={setReason}
              max={200}
              testId="owner-order-withdraw-input-reason"
            />
            <ErrorText error={action.error} context="withdraw" />
          </div>
        }
        confirmText="確認撤回"
        pending={action.pending}
        onConfirm={submit}
        onCancel={() => setOpen(false)}
        confirmTestId="owner-order-withdraw-btn-confirm"
        danger
      />
    </>
  );
};

const CompletionBlock: React.FC<{ detail: OrderDetail; onDone: () => void }> = ({
  detail,
  onDone
}) => {
  const actions = detail.allowedActions ?? [];
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [disputeOpen, setDisputeOpen] = useState(false);
  const [description, setDescription] = useState('');
  const complete = useIdempotentAction();
  const dispute = useIdempotentAction();
  const hint = completionHintText(detail);
  const windowPassed = isPast(detail.disputeWindowEndsAt, detail.serverTime);

  return (
    <SectionCard title="結案">
      {detail.disputeWindowEndsAt && (
        <KV label="可回報問題至" testId="owner-order-detail-dispute-window-ends-at">
          {windowPassed ? '即將自動結案' : formatDateTime(detail.disputeWindowEndsAt)}
        </KV>
      )}
      {hint && (
        <p className="muted" style={{ margin: 0 }} data-testid="owner-order-detail-complete-hint">
          {hint}
        </p>
      )}
      <div className="btn-row">
        {actions.includes('COMPLETE') && (
          <Button
            className="btn-primary"
            onClick={() => {
              complete.reset();
              setConfirmOpen(true);
            }}
            data-testid="owner-order-detail-btn-complete"
          >
            確認結案
          </Button>
        )}
        {actions.includes('REPORT_SERVICE_ISSUE') && !disputeOpen && (
          <Button
            variant="danger-outline"
            onClick={() => {
              dispute.reset();
              setDisputeOpen(true);
            }}
            data-testid="owner-order-detail-btn-report-issue"
          >
            回報問題
          </Button>
        )}
      </div>
      {disputeOpen && (
        <div className="stack">
          <CountedTextArea
            id="service-dispute"
            label="問題說明"
            value={description}
            onChange={setDescription}
            max={500}
            rows={5}
            testId="owner-order-dispute-input-description"
            counterTestId="owner-order-dispute-description-counter"
            showRemaining
          />
          <ErrorText error={dispute.error} context="serviceDispute" />
          <div className="btn-row">
            <Button variant="secondary" onClick={() => setDisputeOpen(false)}>
              取消
            </Button>
            <Button
              variant="danger-outline"
              disabled={dispute.pending || codePointLength(stripText(description)) === 0}
              onClick={async () => {
                const res = await dispute.run((key) =>
                  openServiceDispute(detail.orderId, stripText(description), key)
                );
                if (res) {
                  setDisputeOpen(false);
                  onDone();
                }
              }}
              data-testid="owner-order-dispute-btn-submit"
            >
              {dispute.pending ? '送出中…' : '送出'}
            </Button>
          </div>
        </div>
      )}
      <ConfirmSheet
        isOpen={confirmOpen}
        title="確認結案"
        message={
          <div className="stack">
            <span>確認結案後將無法再回報服務問題，確定要結案嗎？</span>
            <ErrorText error={complete.error} context="complete" />
          </div>
        }
        confirmText="確認結案"
        pending={complete.pending}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={async () => {
          const res = await complete.run((key) => completeOrder(detail.orderId, key));
          if (res) {
            setConfirmOpen(false);
            onDone();
          }
        }}
        confirmTestId="owner-order-complete-btn-confirm"
      />
    </SectionCard>
  );
};

/**
 * 飼主訂單詳情（SD-ORDER §9.1 `/owner/orders/:orderId`、SD-FULFILL §9.1 / §9.2）：E2 為主，
 * 按鈕一律依 `allowedActions`，提示依 `completionHint`，時間判斷一律用 `serverTime`。
 * 頁面不出現 `order-total-amount`（付款區只顯示 payment amount，PRD-007 BR-1）。
 */
const OwnerOrderDetail: React.FC<{ orderId: string }> = ({ orderId }) => {
  const navigate = useNavigate();
  const { data: detail, isLoading, error, refetch } = useOrderDetailQuery(orderId);
  const invalidate = useInvalidateOrder();
  const refresh = () => invalidate(orderId);

  if (isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (error || !detail)
    return (
      <PageContainer>
        <QueryErrorBlock error={error} onRetry={() => refetch()} />
      </PageContainer>
    );

  const actions = detail.allowedActions ?? [];
  const initialPayment = detail.payments?.find((p) => p.purpose === 'INITIAL');
  const withdrawBlocked =
    detail.status === 'PENDING_PAYMENT' &&
    !actions.includes('WITHDRAW') &&
    initialPayment?.status === 'SUBMITTED';

  return (
    <PageContainer testId="owner-order-detail">
      <div className="btn-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>訂單詳情</h2>
        <StatusBadge status={detail.status} />
      </div>
      <SectionCard>
        <KV label="保母">{detail.counterpart?.displayName ?? '—'}</KV>
        <KV label="服務期間">
          {detail.firstServiceDate ?? '—'}
          {detail.lastServiceDate && detail.lastServiceDate !== detail.firstServiceDate
            ? ` ~ ${detail.lastServiceDate}`
            : ''}
        </KV>
        {detail.status === 'PENDING' && detail.reviewExpiresAt && (
          <KV label="保母回覆期限">{formatDateTime(detail.reviewExpiresAt)}</KV>
        )}
      </SectionCard>

      {detail.status === 'CANCELLED' && detail.cancellation && (
        <div role="status" style={INFO_BANNER_STYLE} data-testid="owner-order-detail-cancel-result">
          <strong>{ownerCancelTitle(detail.cancellation.reasonCode)}</strong>
          {detail.cancellation.reason && (
            <div style={{ marginTop: '0.25rem', overflowWrap: 'anywhere' }}>
              原因：{detail.cancellation.reason}
            </div>
          )}
        </div>
      )}
      {detail.status === 'DISPUTED' && (
        <div
          role="status"
          style={INFO_BANNER_STYLE}
          data-testid="owner-order-detail-disputed-banner"
        >
          訂單爭議處理中，請等候平台處理
        </div>
      )}
      {detail.status === 'COMPLETED' && (
        <div role="status" style={INFO_BANNER_STYLE}>
          已結案
          <span data-testid="owner-order-detail-completed-at" style={{ marginLeft: '0.5rem' }}>
            {formatDateTime(detail.completedAt)}
          </span>
        </div>
      )}

      <QuoteBreakdown detail={detail} />
      <OrderGroupsView groups={detail.groups} />

      {actions.includes('WITHDRAW') && <WithdrawBlock detail={detail} onDone={refresh} />}
      {withdrawBlocked && (
        <p
          role="status"
          className="muted"
          data-testid="owner-order-detail-withdraw-blocked-message"
        >
          已上傳付款憑證，無法撤回；如需取消請與保母聯繫
        </p>
      )}

      {(detail.payments?.length ?? 0) > 0 && (
        <PaymentSection
          orderId={orderId}
          serverTime={detail.serverTime}
          refundDisputeHref={
            detail.openModification
              ? `/orders/${orderId}/modifications/${detail.openModification.modificationId}`
              : undefined
          }
        />
      )}

      {(actions.includes('REQUEST_MODIFICATION') || detail.openModification) && (
        <div className="btn-row">
          {actions.includes('REQUEST_MODIFICATION') && (
            <Button
              variant="secondary"
              onClick={() => navigate(`/orders/${orderId}/modify`)}
              data-testid="owner-order-detail-btn-modify"
            >
              申請變更
            </Button>
          )}
          {detail.openModification && (
            <Button
              className="btn-primary"
              onClick={() =>
                navigate(
                  `/orders/${orderId}/modifications/${detail.openModification!.modificationId}`
                )
              }
              data-testid="owner-order-detail-btn-open-modification"
            >
              查看進行中的變更
            </Button>
          )}
        </div>
      )}

      {detail.status === 'IN_PROGRESS' && <CompletionBlock detail={detail} onDone={refresh} />}

      <OrderVisitList orderId={orderId} orderStatus={detail.status} viewer="OWNER" />
      {!['PENDING', 'PENDING_PAYMENT'].includes(detail.status) && (
        <ModificationHistory orderId={orderId} />
      )}
    </PageContainer>
  );
};

export default OwnerOrderDetail;
