import React, { useState } from 'react';
import Button from '../../components/ui/Button';
import StatusBadge from '../../components/ui/StatusBadge';
import StatusChip from '../../components/ui/StatusChip';
import { subStatusTone } from '../../components/ui/statusTone';
import {
  adminTaskStatusLabel,
  adminTaskTypeLabel,
  cancelReasonLabel,
  modificationStatusLabel,
  modificationTypeLabel,
  partyLabel
} from '../../components/ui/statusLabels';
import PaymentSection from '../../components/orders/PaymentSection';
import {
  OrderGroupsView,
  OrderVisitList,
  QuoteBreakdown
} from '../../components/orders/OrderSections';
import {
  CountedTextArea,
  ErrorText,
  KV,
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import { useAdminOrderQuery, useInvalidateOrder, usePaymentsQuery } from '../../hooks/useOrders';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { adminMediate } from '../../api/orderApi';
import type { OrderDetail } from '../../api/orderApi';
import { formatDateTime } from '../../utils/datetime';
import { formatMoney } from '../../utils/money';
import { isValidRequiredText, stripText } from '../../utils/text';

/** 調解（E29，O15 / O17）：已上傳退款必須逐筆標示是否已匯出；可選手動調整金額與依據 */
const MediationBlock: React.FC<{ detail: OrderDetail; onDone: () => void }> = ({
  detail,
  onDone
}) => {
  const { data: payments = [] } = usePaymentsQuery(detail.orderId);
  const submittedRefunds = payments.filter(
    (p) => p.direction === 'REFUND' && p.status === 'SUBMITTED'
  );
  const [decisions, setDecisions] = useState<Record<string, boolean | undefined>>({});
  const [withAdjust, setWithAdjust] = useState(false);
  const [amount, setAmount] = useState('');
  const [basis, setBasis] = useState('');
  const action = useIdempotentAction();
  const amt = amount === '' ? 0 : Number(amount);
  const valid =
    submittedRefunds.every((p) => decisions[p.paymentId] !== undefined) &&
    (!withAdjust || (amt >= 1 && amt <= 999999 && isValidRequiredText(basis, 500)));

  return (
    <SectionCard title="平台調解">
      {submittedRefunds.map((p) => (
        <div key={p.paymentId} className="surface-block stack" style={{ gap: '0.375rem' }}>
          <span>退款 {formatMoney(p.amount)} 是否已實際匯出？</span>
          <div className="btn-row">
            {[true, false].map((v) => (
              <label
                key={String(v)}
                style={{ display: 'flex', gap: '0.25rem', alignItems: 'center' }}
              >
                <input
                  type="radio"
                  name={`refund-${p.paymentId}`}
                  checked={decisions[p.paymentId] === v}
                  onChange={() => setDecisions({ ...decisions, [p.paymentId]: v })}
                  data-testid={`admin-mediation-refund-${p.paymentId}-${v ? 'exported' : 'not-exported'}`}
                />
                {v ? '已匯出' : '未匯出'}
              </label>
            ))}
          </div>
        </div>
      ))}
      <label style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
        <input
          type="checkbox"
          checked={withAdjust}
          onChange={(e) => setWithAdjust(e.target.checked)}
        />
        手動調整金額
      </label>
      {withAdjust && (
        <>
          <div>
            <label className="field-label" htmlFor="mediation-amount">
              調整金額
            </label>
            <input
              id="mediation-amount"
              className="form-input"
              inputMode="numeric"
              value={amount}
              onChange={(e) => setAmount(e.target.value.replace(/\D/g, '').slice(0, 6))}
              data-testid="admin-mediation-input-amount"
            />
          </div>
          <CountedTextArea
            id="mediation-basis"
            label="調整依據"
            value={basis}
            onChange={setBasis}
            max={500}
            testId="admin-mediation-input-basis"
          />
        </>
      )}
      <ErrorText error={action.error} context="paymentDispute" />
      <div className="btn-row">
        <Button
          className="btn-primary"
          disabled={!valid || action.pending}
          onClick={async () => {
            const res = await action.run((key) =>
              adminMediate(
                detail.orderId,
                {
                  ...(submittedRefunds.length
                    ? {
                        refundDecisions: submittedRefunds.map((p) => ({
                          paymentId: p.paymentId,
                          exported: !!decisions[p.paymentId]
                        }))
                      }
                    : {}),
                  ...(withAdjust ? { adjustment: { amount: amt, basis: stripText(basis) } } : {})
                },
                key
              )
            );
            if (res) onDone();
          }}
          data-testid="admin-mediation-btn-submit"
        >
          {action.pending ? '送出中…' : '完成調解'}
        </Button>
      </div>
    </SectionCard>
  );
};

/**
 * ADMIN 訂單頁（SD-ORDER §9.1 `/admin/orders/:orderId`，E27、E10、E12、E13、E16、E29；取代 `/admin/resolve/:orderId`）。
 * 行程唯讀（PRD-008 AC-3.5 情境 4）；其餘版面待 PRD-020 改寫時定義。
 */
const AdminOrderPage: React.FC<{ orderId: string }> = ({ orderId }) => {
  const { data: detail, isLoading, error, refetch } = useAdminOrderQuery(orderId);
  const invalidate = useInvalidateOrder();
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
  return (
    <PageContainer testId="admin-order-detail">
      <div className="btn-row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>訂單（管理端）</h2>
        <StatusBadge status={detail.status} />
      </div>
      <SectionCard>
        <KV label="訂單編號">{detail.orderId.slice(0, 8).toUpperCase()}</KV>
        <KV label="服務期間">
          {detail.firstServiceDate ?? '—'} ~ {detail.lastServiceDate ?? '—'}
        </KV>
        {detail.cancellation && (
          <KV label="取消">{cancelReasonLabel(detail.cancellation.reasonCode)}</KV>
        )}
      </SectionCard>
      <QuoteBreakdown detail={detail} />
      <OrderGroupsView groups={detail.groups} />
      <PaymentSection
        orderId={orderId}
        serverTime={detail.serverTime}
        adminCanVoid={actions.includes('VOID_INITIAL_PAYMENT')}
      />
      {actions.includes('MEDIATE') && (
        <MediationBlock detail={detail} onDone={() => invalidate(orderId)} />
      )}
      <OrderVisitList orderId={orderId} orderStatus={detail.status} viewer="ADMIN" />
      <SectionCard title="待辦">
        {(detail.adminTasks ?? []).length === 0 ? (
          <span className="muted">無</span>
        ) : (
          detail.adminTasks!.map((t) => (
            <div key={t.taskId} className="kv-row">
              <span>{adminTaskTypeLabel(t.type)}</span>
              <span>
                {adminTaskStatusLabel(t.status)}（{formatDateTime(t.createdAt)}）
              </span>
            </div>
          ))
        )}
      </SectionCard>
      <SectionCard title="變更紀錄">
        {(detail.modifications ?? []).length === 0 ? (
          <span className="muted">尚無變更紀錄</span>
        ) : (
          detail.modifications!.map((m) => (
            <div key={m.modificationId} className="btn-row" style={{ alignItems: 'center' }}>
              <span>
                {modificationTypeLabel(m.type)}（{partyLabel(m.initiatedBy)}）
              </span>
              <StatusChip
                label={modificationStatusLabel(m.status)}
                tone={subStatusTone(m.status)}
              />
            </div>
          ))
        )}
      </SectionCard>
      <SectionCard title="稽核紀錄">
        {(detail.logs ?? []).map((l, i) => (
          <div key={i} className="kv-row">
            <span>{formatDateTime(l.createdAt)}</span>
            <span>
              {l.actionType}（{partyLabel(l.operatorRole)}）
            </span>
          </div>
        ))}
      </SectionCard>
    </PageContainer>
  );
};

export default AdminOrderPage;
