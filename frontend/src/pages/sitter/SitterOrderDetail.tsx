import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
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
import {
  orderKeys,
  useEvaluationQuery,
  useInvalidateOrder,
  useOrderDetailQuery
} from '../../hooks/useOrders';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { declineOrder, quoteOrder, sitterCancelOrder } from '../../api/orderApi';
import type { Evaluation, OrderDetail } from '../../api/orderApi';
import { getApiError, payloadNumber } from '../../api/apiError';
import { formatDateTime, isPast } from '../../utils/datetime';
import { formatMoney } from '../../utils/money';
import { isValidRequiredText, stripText } from '../../utils/text';
import { useQueryClient } from '@tanstack/react-query';

const QUICK_REASONS = ['檔期已滿', '超出服務範圍', '無法照顧此類毛孩', '其他'];

/** 婉拒（O3）/ 付款前取消（O7）共用對話框（PRD-027 §4.1 / §4.3） */
const ReasonDialog: React.FC<{
  kind: 'decline' | 'cancel';
  isOpen: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<boolean>;
  pending: boolean;
  error: unknown;
  done: boolean;
  referralHref?: string;
}> = ({ kind, isOpen, onClose, onSubmit, pending, error, done, referralHref }) => {
  const [reason, setReason] = useState('');
  const p = kind === 'decline' ? 'sitter-order-decline' : 'sitter-order-cancel';
  const notice =
    kind === 'decline'
      ? '婉拒後飼主將收到通知，含您填寫的原因'
      : '取消後飼主將收到通知，含您填寫的原因';
  if (!isOpen) return null;
  return (
    <ConfirmSheet
      isOpen
      title={kind === 'decline' ? '婉拒預約' : '取消訂單'}
      message={
        done ? (
          <div className="stack">
            <span>{kind === 'decline' ? '已婉拒此預約' : '已取消此訂單'}</span>
            {kind === 'decline' && referralHref && (
              <Link
                to={referralHref}
                data-testid="sitter-order-decline-btn-referral"
                className="btn btn-primary"
                style={{ textDecoration: 'none' }}
              >
                轉介信任圈夥伴
              </Link>
            )}
          </div>
        ) : (
          <div className="stack">
            <span className="muted">{notice}</span>
            <div className="btn-row">
              {QUICK_REASONS.map((r, i) => (
                <Button
                  key={r}
                  variant="secondary"
                  style={{ padding: '0.375rem 0.75rem', fontSize: '0.8125rem' }}
                  onClick={() => setReason(r)}
                  data-testid={`${p}-btn-quick-reason-${i + 1}`}
                >
                  {r}
                </Button>
              ))}
            </div>
            <CountedTextArea
              id={`${p}-reason`}
              label="原因"
              value={reason}
              onChange={setReason}
              max={200}
              testId={`${p}-input-reason`}
            />
            <ErrorText error={error} context="decline" />
          </div>
        )
      }
      confirmText={done ? '關閉' : kind === 'decline' ? '確認婉拒' : '確認取消'}
      pending={pending}
      confirmDisabled={!done && !isValidRequiredText(reason, 200)}
      onCancel={onClose}
      onConfirm={async () => {
        if (done) onClose();
        else await onSubmit(stripText(reason));
      }}
      confirmTestId={done ? undefined : `${p}-btn-confirm`}
      danger={!done}
    />
  );
};

/** 評估 / 報價區（PRD-006，E5 + E6） */
const EvaluationBlock: React.FC<{
  detail: OrderDetail;
  evaluation: Evaluation;
  onQuoted: () => void;
}> = ({ detail, evaluation, onQuoted }) => {
  const qc = useQueryClient();
  const [adjust, setAdjust] = useState(false);
  const [surcharge, setSurcharge] = useState('');
  const [discount, setDiscount] = useState('');
  const [notes, setNotes] = useState('');
  const [planHint, setPlanHint] = useState(false);
  const [priceNotice, setPriceNotice] = useState<string | null>(null);
  const action = useIdempotentAction();
  const pc = evaluation.precheck;
  const blocked = pc.subscriptionLocked || pc.quotaReached || pc.payoutAccountMissing;
  const expired = pc.reviewExpired;
  const quoteDisabled = blocked || expired || action.pending;

  const toInt = (s: string) => (s === '' ? 0 : Number(s));
  const s = toInt(surcharge);
  const d = toInt(discount);
  const hasAdj = s > 0 || d > 0;
  const total = evaluation.baseAmount + s - d;
  const adjustValid =
    Number.isInteger(s) &&
    Number.isInteger(d) &&
    s >= 0 &&
    d >= 0 &&
    s <= 999999 &&
    d <= 999999 &&
    (!hasAdj || isValidRequiredText(notes, 200)) &&
    total >= 1 &&
    total <= 999999;

  const submit = async (withAdj: boolean) => {
    setPriceNotice(null);
    const res = await action.run(async (key) => {
      try {
        return await quoteOrder(
          detail.orderId,
          {
            expectedAmount: evaluation.baseAmount,
            ...(withAdj && s > 0 ? { surchargeAmount: s } : {}),
            ...(withAdj && d > 0 ? { discountAmount: d } : {}),
            ...(withAdj && hasAdj ? { pricingNotes: stripText(notes) } : {})
          },
          key
        );
      } catch (e) {
        // PRICE_CHANGED：重新載入金額並提示（PRD-006 EX-13）
        const err = getApiError(e);
        if (err.code === 'PRICE_CHANGED') {
          setPriceNotice(
            `訂單金額已更新為 ${formatMoney(payloadNumber(err, 'baseAmount'))}，請確認後再送出`
          );
          qc.invalidateQueries({ queryKey: orderKeys.evaluation(detail.orderId) });
        }
        throw e;
      }
    });
    if (res) {
      // SD-ORDER §9.1：報價成功留在本頁，以回應立即渲染為待付款，E2 / 列表失效重抓（不整頁 reload）
      qc.setQueryData(orderKeys.detail(detail.orderId), (old: OrderDetail | undefined) =>
        old ? { ...old, status: res.status } : old
      );
      onQuoted();
    }
  };

  const err = action.error ? getApiError(action.error) : null;

  return (
    <SectionCard title="評估與報價">
      <KV label="評估期限" testId="sitter-order-eval-review-expires-at">
        {formatDateTime(evaluation.reviewExpiresAt)}
      </KV>
      <KV label="飼主">
        {evaluation.owner?.displayName ?? '—'}
        {evaluation.isRegularOwner && (
          <span data-testid="sitter-order-eval-regular-badge" style={{ marginLeft: '0.5rem' }}>
            熟客
          </span>
        )}
      </KV>
      <KV label="原始試算">{formatMoney(evaluation.baseAmount)}</KV>

      <div className="stack" style={{ gap: '0.5rem' }}>
        <strong style={{ fontSize: '0.875rem' }}>事前問卷</strong>
        {evaluation.questionnaire?.status === 'NOT_REQUIRED' ? (
          <span className="muted" data-testid="sitter-order-eval-questionnaire-exempt-label">
            {evaluation.questionnaire.exemptReason === 'REGULAR_OWNER'
              ? '免填：熟客'
              : '免填：保母無題目'}
          </span>
        ) : (
          <div
            className="stack"
            style={{ gap: '0.5rem' }}
            data-testid="sitter-order-eval-questionnaire-answers"
          >
            {(evaluation.questionnaire?.answers ?? []).map((a) => (
              <div key={a.questionId} className="surface-block">
                <div className="muted">{a.questionText}</div>
                <div style={{ overflowWrap: 'anywhere' }}>
                  {Array.isArray(a.answer) ? a.answer.join('、') : a.answer || '（未作答）'}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {(blocked || expired) && (
        <div
          role="status"
          style={INFO_BANNER_STYLE}
          data-testid="sitter-order-eval-precheck-banner"
        >
          {pc.subscriptionLocked && <div>您的訂閱已到期或遭停權，暫時無法接新訂單</div>}
          {pc.quotaReached && (
            <div>
              本月額度已滿{' '}
              <Link to="/sitter/gatekeeper" data-testid="sitter-order-eval-precheck-upgrade-link">
                升級方案
              </Link>
            </div>
          )}
          {pc.payoutAccountMissing && (
            <div>
              請先設定收款帳號 <Link to="/sitter/payment-settings">前往設定</Link>
            </div>
          )}
          {expired && <div>此預約已超過評估期限</div>}
        </div>
      )}

      <div className="btn-row">
        <Button
          className="btn-primary"
          disabled={quoteDisabled}
          onClick={() => submit(false)}
          data-testid="sitter-order-eval-btn-quote-original"
        >
          原價接單
        </Button>
        <Button
          variant="secondary"
          disabled={quoteDisabled}
          onClick={() => {
            if (!pc.planAllowsAdjustment) setPlanHint(true);
            else setAdjust(true);
          }}
          data-testid="sitter-order-eval-btn-quote-adjust"
        >
          {pc.planAllowsAdjustment ? '調整價格' : '🔒 調整價格'}
        </Button>
      </div>
      {planHint && (
        <p className="muted" role="status" style={{ margin: 0 }}>
          手動調價為專業版功能 <Link to="/sitter/gatekeeper">升級方案</Link>
        </p>
      )}

      {adjust && (
        <div className="stack">
          <div className="btn-row">
            <div style={{ flex: '1 1 7rem', minWidth: 0 }}>
              <label className="field-label" htmlFor="eval-surcharge">
                加價
              </label>
              <input
                id="eval-surcharge"
                className="form-input"
                inputMode="numeric"
                value={surcharge}
                onChange={(e) => setSurcharge(e.target.value.replace(/\D/g, '').slice(0, 6))}
                data-testid="sitter-order-eval-input-surcharge"
              />
            </div>
            <div style={{ flex: '1 1 7rem', minWidth: 0 }}>
              <label className="field-label" htmlFor="eval-discount">
                折扣
              </label>
              <input
                id="eval-discount"
                className="form-input"
                inputMode="numeric"
                value={discount}
                onChange={(e) => setDiscount(e.target.value.replace(/\D/g, '').slice(0, 6))}
                data-testid="sitter-order-eval-input-discount"
              />
            </div>
          </div>
          <CountedTextArea
            id="eval-notes"
            label="調整原因"
            value={notes}
            onChange={setNotes}
            max={200}
            testId="sitter-order-eval-input-pricing-notes"
          />
          <KV label="報價總額">{formatMoney(total)}</KV>
          {total < 1 && <p className="field-error">報價總額至少需 $ 1</p>}
          <div className="btn-row">
            <Button
              className="btn-primary"
              disabled={quoteDisabled || !adjustValid}
              onClick={() => submit(true)}
              data-testid="sitter-order-eval-btn-quote-submit"
            >
              {action.pending ? '送出中…' : '送出報價'}
            </Button>
          </div>
        </div>
      )}
      {priceNotice ? (
        <p role="alert" className="field-error">
          {priceNotice}
        </p>
      ) : (
        <ErrorText error={action.error} context="quote" />
      )}
      {err?.code === 'PAYOUT_ACCOUNT_REQUIRED' && (
        <Link to="/sitter/payment-settings">前往設定收款帳號</Link>
      )}
    </SectionCard>
  );
};

/**
 * 保母訂單詳情（SD-ORDER §9.1 `/sitter/orders/:orderId`；`/sitter/eval/:orderId` 轉址至此）：
 * PENDING 時含評估與報價（E5、E6）、婉拒（E7）；PENDING_PAYMENT 可付款前取消（E9）；付款核對（E10–E15）；
 * 行程（F1，任何狀態都顯示）；結案資訊（PRD-009）。
 */
const SitterOrderDetail: React.FC<{ orderId: string }> = ({ orderId }) => {
  const navigate = useNavigate();
  const { data: detail, isLoading, error, refetch } = useOrderDetailQuery(orderId);
  const evalQuery = useEvaluationQuery(orderId, detail?.status === 'PENDING');
  const invalidate = useInvalidateOrder();
  const refresh = () => invalidate(orderId);
  const [dialog, setDialog] = useState<'decline' | 'cancel' | null>(null);
  const [dialogDone, setDialogDone] = useState(false);
  const decline = useIdempotentAction();

  if (isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (error || !detail)
    return (
      <PageContainer>
        <QueryErrorBlock error={error} context="quote" onRetry={() => refetch()} />
      </PageContainer>
    );

  const actions = detail.allowedActions ?? [];
  const initialPayment = detail.payments?.find((p) => p.purpose === 'INITIAL');
  const cancelBlocked =
    detail.status === 'PENDING_PAYMENT' &&
    !actions.includes('SITTER_CANCEL') &&
    initialPayment?.status === 'SUBMITTED';
  const autoCompletePassed = isPast(detail.disputeWindowEndsAt, detail.serverTime);

  const submitReason = async (reason: string) => {
    const res = await decline.run((key) =>
      dialog === 'decline'
        ? declineOrder(orderId, reason, key)
        : sitterCancelOrder(orderId, reason, key)
    );
    if (res) {
      setDialogDone(true);
      refresh();
      return true;
    }
    return false;
  };

  return (
    <PageContainer testId="sitter-order-detail">
      <div className="btn-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>訂單詳情</h2>
        <StatusBadge status={detail.status} />
      </div>
      <SectionCard>
        <KV label="飼主">{detail.counterpart?.displayName ?? '—'}</KV>
        <KV label="服務期間">
          {detail.firstServiceDate ?? '—'}
          {detail.lastServiceDate && detail.lastServiceDate !== detail.firstServiceDate
            ? ` ~ ${detail.lastServiceDate}`
            : ''}
        </KV>
        {detail.disputeWindowEndsAt && (
          <KV label="預計自動結案時間" testId="sitter-order-detail-auto-complete-at">
            {autoCompletePassed ? '即將自動結案' : formatDateTime(detail.disputeWindowEndsAt)}
          </KV>
        )}
      </SectionCard>

      {detail.status === 'CANCELLED' && detail.cancellation && (
        <div
          role="status"
          style={INFO_BANNER_STYLE}
          data-testid="sitter-order-detail-cancel-result"
        >
          <strong>{cancelReasonLabel(detail.cancellation.reasonCode)}</strong>
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
          data-testid="sitter-order-detail-disputed-banner"
        >
          訂單爭議處理中，請等候平台處理
        </div>
      )}
      {detail.status === 'COMPLETED' && (
        <div role="status" style={INFO_BANNER_STYLE}>
          已結案
          <span data-testid="sitter-order-detail-completed-at" style={{ marginLeft: '0.5rem' }}>
            {formatDateTime(detail.completedAt)}
          </span>
        </div>
      )}

      <QuoteBreakdown detail={detail} />
      <OrderGroupsView
        groups={evalQuery.data?.groups ?? detail.groups}
        petTestIdPrefix={detail.status === 'PENDING' ? 'sitter-order-eval-pet-' : undefined}
      />

      {detail.status === 'PENDING' &&
        (evalQuery.isLoading ? (
          <LoadingBlock />
        ) : evalQuery.error ? (
          <QueryErrorBlock
            error={evalQuery.error}
            context="quote"
            onRetry={() => evalQuery.refetch()}
          />
        ) : evalQuery.data ? (
          <EvaluationBlock detail={detail} evaluation={evalQuery.data} onQuoted={refresh} />
        ) : null)}

      {(actions.includes('DECLINE') || actions.includes('SITTER_CANCEL')) && (
        <div className="btn-row">
          {actions.includes('DECLINE') && (
            <Button
              variant="danger-outline"
              onClick={() => {
                decline.reset();
                setDialogDone(false);
                setDialog('decline');
              }}
              data-testid="sitter-order-eval-btn-decline"
            >
              婉拒預約
            </Button>
          )}
          {actions.includes('SITTER_CANCEL') && (
            <Button
              variant="danger-outline"
              onClick={() => {
                decline.reset();
                setDialogDone(false);
                setDialog('cancel');
              }}
              data-testid="sitter-order-detail-btn-cancel"
            >
              取消訂單
            </Button>
          )}
        </div>
      )}
      {cancelBlocked && (
        <p role="status" className="muted" data-testid="sitter-order-cancel-blocked-message">
          飼主已上傳付款憑證，請先核對或駁回
        </p>
      )}

      {(detail.payments?.length ?? 0) > 0 && (
        <PaymentSection orderId={orderId} serverTime={detail.serverTime} />
      )}

      {(actions.includes('REQUEST_MODIFICATION') || detail.openModification) && (
        <div className="btn-row">
          {actions.includes('REQUEST_MODIFICATION') && (
            <Button
              variant="secondary"
              onClick={() => navigate(`/orders/${orderId}/modify`)}
              data-testid="sitter-order-detail-btn-modify"
            >
              提出變更
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
              data-testid="sitter-order-detail-btn-open-modification"
            >
              查看進行中的變更
            </Button>
          )}
        </div>
      )}

      <OrderVisitList orderId={orderId} orderStatus={detail.status} viewer="SITTER" />
      {!['PENDING', 'PENDING_PAYMENT'].includes(detail.status) && (
        <ModificationHistory orderId={orderId} />
      )}

      <ReasonDialog
        kind={dialog ?? 'decline'}
        isOpen={dialog !== null}
        onClose={() => setDialog(null)}
        onSubmit={submitReason}
        pending={decline.pending}
        error={decline.error}
        done={dialogDone}
        referralHref={
          detail.counterpart
            ? `/sitter/referral/${detail.counterpart.userId}?orderId=${orderId}`
            : undefined
        }
      />
    </PageContainer>
  );
};

export default SitterOrderDetail;
