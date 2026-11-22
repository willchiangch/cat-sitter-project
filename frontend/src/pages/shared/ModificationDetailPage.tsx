import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import StatusChip from '../../components/ui/StatusChip';
import { subStatusTone } from '../../components/ui/statusTone';
import {
  modificationStatusLabel,
  modificationTypeLabel,
  partyLabel
} from '../../components/ui/statusLabels';
import PaymentSection from '../../components/orders/PaymentSection';
import ModificationEditor from '../../components/orders/ModificationEditor';
import AdjustmentFields from '../../components/orders/AdjustmentFields';
import {
  diffText,
  estimateSystemAmount,
  isDraftValid,
  type ModificationDraft
} from '../../components/orders/modificationDraft';
import {
  CountedTextArea,
  ErrorText,
  KV,
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import {
  useInvalidateOrder,
  useModificationQuery,
  useOrderDetailQuery
} from '../../hooks/useOrders';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import {
  acceptModification,
  openPaymentDispute,
  quoteModification,
  rejectModification,
  withdrawModification
} from '../../api/orderApi';
import type { ModificationContent, ModificationDetail, OrderDetail } from '../../api/orderApi';
import { formatMoney } from '../../utils/money';
import { formatDateTime, isPast } from '../../utils/datetime';
import { codePointLength, isValidRequiredText, stripText } from '../../utils/text';

const ContentView: React.FC<{
  title: string;
  content?: ModificationContent;
  detail: OrderDetail;
}> = ({ title, content, detail }) => {
  if (!content) return null;
  const visits = new Map((detail.visits ?? []).map((v) => [v.visitId, v]));
  const plans = new Map((detail.groups ?? []).map((g) => [g.planId, g.planTitle]));
  const removed = content.removeVisitIds ?? [];
  const added = content.addItems ?? [];
  return (
    <div className="stack" style={{ gap: '0.25rem' }}>
      <strong style={{ fontSize: '0.875rem' }}>{title}</strong>
      {removed.length > 0 && (
        <span className="muted" style={{ overflowWrap: 'anywhere' }}>
          減少：
          {removed
            .map((id) => {
              const v = visits.get(id);
              return v ? `${v.serviceDate} ${plans.get(v.planId) ?? ''}` : id.slice(0, 8);
            })
            .join('、')}
        </span>
      )}
      {added.length > 0 && (
        <span className="muted" style={{ overflowWrap: 'anywhere' }}>
          新增：
          {added.map((a) => `${a.date} ${plans.get(a.planId) ?? ''} × ${a.dailyCount}`).join('、')}
        </span>
      )}
      {removed.length === 0 && added.length === 0 && <span className="muted">—</span>}
    </div>
  );
};

/** 保母報價表單（E20，PRD-016 §4.3）：新內容預填飼主期望內容 */
const QuoteForm: React.FC<{ detail: OrderDetail; mod: ModificationDetail; onDone: () => void }> = ({
  detail,
  mod,
  onDone
}) => {
  const cancelAll = mod.type === 'CANCEL_ALL';
  const [draft, setDraft] = useState<ModificationDraft>({
    removeVisitIds: mod.requestedContent?.removeVisitIds ?? [],
    addItems: mod.requestedContent?.addItems ?? []
  });
  const [adj, setAdj] = useState({ surcharge: '', discount: '', reason: '' });
  const action = useIdempotentAction();
  const s = adj.surcharge === '' ? 0 : Number(adj.surcharge);
  const d = adj.discount === '' ? 0 : Number(adj.discount);
  const hasAdj = detail.modificationPricingMode === 'CUSTOM_ALLOWED' && (s > 0 || d > 0);
  const systemAmount = estimateSystemAmount(detail, draft, cancelAll);
  const newTotal = systemAmount + (hasAdj ? s - d : 0);
  const valid =
    (cancelAll || isDraftValid(draft)) &&
    (!hasAdj || isValidRequiredText(adj.reason, 200)) &&
    newTotal >= 0 &&
    newTotal <= 999999;

  return (
    <SectionCard title="報價">
      {cancelAll ? (
        <p className="muted" style={{ margin: 0 }}>
          整筆取消：取消報價當下所有待執行行程
        </p>
      ) : (
        <ModificationEditor detail={detail} draft={draft} onChange={setDraft} />
      )}
      <AdjustmentFields
        detail={detail}
        surcharge={adj.surcharge}
        discount={adj.discount}
        reason={adj.reason}
        onChange={(p) => setAdj({ ...adj, ...p })}
      />
      <KV label="系統試算新總額">{formatMoney(systemAmount)}</KV>
      <KV label="新總額">{formatMoney(newTotal)}</KV>
      {detail.netPaid !== undefined && <KV label="差額">{diffText(newTotal - detail.netPaid)}</KV>}
      <p className="muted" style={{ margin: 0 }}>
        金額以送出後系統計算為準
      </p>
      <ErrorText error={action.error} context="modification" />
      <div className="btn-row">
        <Button
          className="btn-primary"
          disabled={!valid || action.pending}
          onClick={async () => {
            const res = await action.run((key) =>
              quoteModification(
                detail.orderId,
                mod.modificationId,
                {
                  ...(cancelAll
                    ? {}
                    : { removeVisitIds: draft.removeVisitIds, addItems: draft.addItems }),
                  ...(hasAdj
                    ? {
                        ...(s > 0 ? { surchargeAmount: s } : {}),
                        ...(d > 0 ? { discountAmount: d } : {}),
                        adjustmentReason: stripText(adj.reason)
                      }
                    : {})
                },
                key
              )
            );
            if (res) onDone();
          }}
          data-testid="modification-btn-quote"
        >
          {action.pending ? '送出中…' : '送出報價'}
        </Button>
      </div>
    </SectionCard>
  );
};

/**
 * 協商頁（SD-ORDER §9.1 `/orders/:orderId/modifications/:modificationId`，E19、E20–E24；
 * 取代 `/sitter/orders/:orderId/quote`、`/owner/orders/:orderId/modification-confirm`）。
 * 動作依 E19 `allowedActions`（後端已依 PRD-016 §4.6 與逾期計算）。
 */
const ModificationDetailPage: React.FC<{ orderId: string; modificationId: string }> = ({
  orderId,
  modificationId
}) => {
  const navigate = useNavigate();
  const orderQ = useOrderDetailQuery(orderId);
  const modQ = useModificationQuery(orderId, modificationId);
  const invalidate = useInvalidateOrder();
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [disputeOpen, setDisputeOpen] = useState(false);
  const [disputeText, setDisputeText] = useState('');
  const reject = useIdempotentAction();
  const withdraw = useIdempotentAction();
  const accept = useIdempotentAction();
  const dispute = useIdempotentAction();

  if (orderQ.isLoading || modQ.isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (orderQ.error || modQ.error || !orderQ.data || !modQ.data)
    return (
      <PageContainer>
        <QueryErrorBlock
          error={orderQ.error ?? modQ.error}
          context="modification"
          onRetry={() => {
            orderQ.refetch();
            modQ.refetch();
          }}
        />
      </PageContainer>
    );

  const detail = orderQ.data;
  const mod = modQ.data;
  const isSitter = detail.role === 'SITTER';
  const actions = mod.allowedActions ?? [];
  const open = mod.status === 'REQUESTED' || mod.status === 'QUOTED';
  const expired = mod.status === 'EXPIRED' || (open && isPast(mod.expiresAt, detail.serverTime));
  const refresh = () => invalidate(orderId);
  const backHref = `/${isSitter ? 'sitter' : 'owner'}/orders/${orderId}`;

  return (
    <PageContainer testId="modification-detail">
      <div className="btn-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>訂單變更</h2>
        <StatusChip
          label={modificationStatusLabel(mod.status)}
          tone={subStatusTone(mod.status)}
          status={mod.status}
          testId="modification-detail-status"
        />
      </div>

      <SectionCard>
        <KV label="類型">{modificationTypeLabel(mod.type)}</KV>
        <KV label="提出者">{partyLabel(mod.initiatedBy)}</KV>
        {mod.reason && <KV label="原因">{mod.reason}</KV>}
        {open && (
          <KV label="回應期限" testId="modification-detail-expires-at">
            {formatDateTime(mod.expiresAt)}
          </KV>
        )}
        {mod.endedAt && <KV label="結束時間">{formatDateTime(mod.endedAt)}</KV>}
        {mod.rejectReason && <KV label="拒絕原因">{mod.rejectReason}</KV>}
        <ContentView title="期望內容" content={mod.requestedContent} detail={detail} />
        <ContentView title="報價內容" content={mod.quotedContent} detail={detail} />
      </SectionCard>

      {mod.newTotalAmount !== undefined && (
        <SectionCard title="金額" testId="modification-detail-diff">
          {mod.systemAmount !== undefined && (
            <KV label="系統計算新總額">{formatMoney(mod.systemAmount)}</KV>
          )}
          {!!mod.surchargeAmount && <KV label="加價">{formatMoney(mod.surchargeAmount)}</KV>}
          {!!mod.discountAmount && <KV label="折扣">{formatMoney(-mod.discountAmount)}</KV>}
          {mod.adjustmentReason && <KV label="調整原因">{mod.adjustmentReason}</KV>}
          <KV label="新總額">{formatMoney(mod.newTotalAmount)}</KV>
          {mod.netPaid !== undefined && <KV label="已收淨額">{formatMoney(mod.netPaid)}</KV>}
          <KV label="差額">{diffText(mod.diff)}</KV>
        </SectionCard>
      )}

      {expired && (
        <p role="status" className="muted" data-testid="modification-detail-expired-message">
          此變更請求已逾期
        </p>
      )}

      {!expired && actions.includes('QUOTE') && (
        <QuoteForm detail={detail} mod={mod} onDone={refresh} />
      )}

      {!expired &&
        (actions.includes('ACCEPT') ||
          actions.includes('REJECT') ||
          actions.includes('WITHDRAW')) && (
          <div className="stack">
            <div className="btn-row">
              {actions.includes('ACCEPT') && (
                <Button
                  className="btn-primary"
                  disabled={accept.pending}
                  onClick={async () => {
                    const res = await accept.run((key) =>
                      acceptModification(orderId, modificationId, key)
                    );
                    if (res) refresh();
                  }}
                  data-testid="modification-btn-accept"
                >
                  {accept.pending ? '處理中…' : '同意變更'}
                </Button>
              )}
              {actions.includes('REJECT') && !rejectOpen && (
                <Button
                  variant="danger-outline"
                  onClick={async () => {
                    if (isSitter) {
                      setRejectOpen(true);
                      return;
                    }
                    const res = await reject.run((key) =>
                      rejectModification(orderId, modificationId, undefined, key)
                    );
                    if (res) refresh();
                  }}
                  disabled={reject.pending}
                  data-testid="modification-btn-reject"
                >
                  拒絕變更
                </Button>
              )}
              {actions.includes('WITHDRAW') && (
                <Button
                  variant="secondary"
                  disabled={withdraw.pending}
                  onClick={async () => {
                    const res = await withdraw.run((key) =>
                      withdrawModification(orderId, modificationId, key)
                    );
                    if (res) refresh();
                  }}
                  data-testid="modification-btn-withdraw"
                >
                  撤回請求
                </Button>
              )}
            </div>
            {rejectOpen && (
              <div className="stack">
                <CountedTextArea
                  id="mod-reject-reason"
                  label="拒絕原因（必填）"
                  value={rejectReason}
                  onChange={setRejectReason}
                  max={200}
                  testId="modification-input-reject-reason"
                />
                <div className="btn-row">
                  <Button variant="secondary" onClick={() => setRejectOpen(false)}>
                    取消
                  </Button>
                  <Button
                    variant="danger-outline"
                    disabled={reject.pending || !isValidRequiredText(rejectReason, 200)}
                    onClick={async () => {
                      const res = await reject.run((key) =>
                        rejectModification(orderId, modificationId, stripText(rejectReason), key)
                      );
                      if (res) {
                        setRejectOpen(false);
                        refresh();
                      }
                    }}
                    data-testid="modification-btn-reject-confirm"
                  >
                    {reject.pending ? '送出中…' : '確認拒絕'}
                  </Button>
                </div>
              </div>
            )}
            <ErrorText
              error={accept.error ?? reject.error ?? withdraw.error}
              context={accept.error ? 'settlement' : 'modification'}
            />
          </div>
        )}

      {mod.payment && (
        <PaymentSection
          orderId={orderId}
          serverTime={detail.serverTime}
          title={mod.payment.direction === 'REFUND' ? '退款' : '補款'}
          filter={(p) => p.paymentId === mod.payment!.paymentId}
        />
      )}

      {(actions.includes('PAYMENT_DISPUTE') || mod.disputeAvailableAt) && (
        <SectionCard title="款項爭議">
          {mod.disputeAvailableAt && (
            <KV label="可申報時間" testId="payment-dispute-available-at">
              {formatDateTime(mod.disputeAvailableAt)}
            </KV>
          )}
          {actions.includes('PAYMENT_DISPUTE') && !disputeOpen && (
            <div className="btn-row">
              <Button
                variant="danger-outline"
                onClick={() => setDisputeOpen(true)}
                data-testid="modification-btn-payment-dispute"
              >
                申報款項爭議
              </Button>
            </div>
          )}
          {disputeOpen && (
            <div className="stack">
              <CountedTextArea
                id="payment-dispute"
                label="爭議說明"
                value={disputeText}
                onChange={setDisputeText}
                max={500}
                rows={5}
                testId="payment-dispute-input-description"
              />
              <ErrorText error={dispute.error} context="paymentDispute" />
              <div className="btn-row">
                <Button variant="secondary" onClick={() => setDisputeOpen(false)}>
                  取消
                </Button>
                <Button
                  variant="danger-outline"
                  disabled={dispute.pending || codePointLength(stripText(disputeText)) === 0}
                  onClick={async () => {
                    const res = await dispute.run((key) =>
                      openPaymentDispute(orderId, stripText(disputeText), key)
                    );
                    if (res) {
                      setDisputeOpen(false);
                      refresh();
                    }
                  }}
                  data-testid="payment-dispute-btn-submit"
                >
                  {dispute.pending ? '送出中…' : '送出'}
                </Button>
              </div>
            </div>
          )}
        </SectionCard>
      )}

      <div className="btn-row">
        <Button variant="secondary" onClick={() => navigate(backHref)}>
          返回訂單
        </Button>
      </div>
    </PageContainer>
  );
};

export default ModificationDetailPage;
