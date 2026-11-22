import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
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
import { useInvalidateOrder, useOrderDetailQuery } from '../../hooks/useOrders';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { initiateModification } from '../../api/orderApi';
import { formatMoney } from '../../utils/money';
import { isValidRequiredText, stripText } from '../../utils/text';

/**
 * 變更精靈（SD-ORDER §9.1 `/orders/:orderId/modify`，E2 + E17；PRD-016 §4.1 / §4.2）。
 * 飼主送出 → REQUESTED；保母送出（同時報價）→ QUOTED。成功後進協商頁。
 */
const OrderModificationWizard: React.FC<{ orderId: string }> = ({ orderId }) => {
  const navigate = useNavigate();
  const { data: detail, isLoading, error, refetch } = useOrderDetailQuery(orderId);
  const invalidate = useInvalidateOrder();
  const [type, setType] = useState<'CHANGE' | 'CANCEL_ALL'>('CHANGE');
  const [draft, setDraft] = useState<ModificationDraft>({ removeVisitIds: [], addItems: [] });
  const [reason, setReason] = useState('');
  const [adj, setAdj] = useState({ surcharge: '', discount: '', reason: '' });
  const action = useIdempotentAction();

  if (isLoading)
    return (
      <PageContainer>
        <LoadingBlock />
      </PageContainer>
    );
  if (error || !detail)
    return (
      <PageContainer>
        <QueryErrorBlock error={error} context="modification" onRetry={() => refetch()} />
      </PageContainer>
    );

  const isSitter = detail.role === 'SITTER';
  const backHref = `/${isSitter ? 'sitter' : 'owner'}/orders/${orderId}`;
  if (!(detail.allowedActions ?? []).includes('REQUEST_MODIFICATION')) {
    return (
      <PageContainer>
        <p className="muted">
          {detail.openModification ? '此訂單已有進行中的變更請求' : '此訂單目前無法變更'}
        </p>
        <div className="btn-row">
          <Button variant="secondary" onClick={() => navigate(backHref)}>
            返回訂單
          </Button>
        </div>
      </PageContainer>
    );
  }

  const s = adj.surcharge === '' ? 0 : Number(adj.surcharge);
  const d = adj.discount === '' ? 0 : Number(adj.discount);
  const hasAdj =
    isSitter && detail.modificationPricingMode === 'CUSTOM_ALLOWED' && (s > 0 || d > 0);
  const systemAmount = estimateSystemAmount(detail, draft, type === 'CANCEL_ALL');
  const newTotal = systemAmount + (hasAdj ? s - d : 0);
  const valid =
    isValidRequiredText(reason, 200) &&
    (type === 'CANCEL_ALL' || isDraftValid(draft)) &&
    (!hasAdj || isValidRequiredText(adj.reason, 200)) &&
    newTotal >= 0 &&
    newTotal <= 999999;

  const submit = async () => {
    const res = await action.run((key) =>
      initiateModification(
        orderId,
        {
          type,
          reason: stripText(reason),
          ...(type === 'CHANGE'
            ? { removeVisitIds: draft.removeVisitIds, addItems: draft.addItems }
            : {}),
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
    if (res) {
      await invalidate(orderId);
      navigate(`/orders/${orderId}/modifications/${res.modificationId}`);
    }
  };

  return (
    <PageContainer testId="order-modify-wizard">
      <h2 style={{ margin: 0, color: 'var(--color-on-surface)' }}>
        {isSitter ? '提出變更' : '申請變更'}
      </h2>
      <SectionCard title="變更類型">
        <div className="btn-row">
          {(['CHANGE', 'CANCEL_ALL'] as const).map((t) => (
            <label key={t} style={{ display: 'flex', gap: '0.375rem', alignItems: 'center' }}>
              <input
                type="radio"
                name="modify-type"
                checked={type === t}
                onChange={() => setType(t)}
                data-testid={`order-modify-type-${t}`}
              />
              {t === 'CHANGE' ? '變更行程' : '整筆取消'}
            </label>
          ))}
        </div>
        {type === 'CANCEL_ALL' && (
          <p className="muted" style={{ margin: 0 }}>
            將取消所有待執行行程
          </p>
        )}
      </SectionCard>

      {type === 'CHANGE' && (
        <SectionCard title="變更內容">
          <ModificationEditor detail={detail} draft={draft} onChange={setDraft} />
        </SectionCard>
      )}

      <SectionCard title="原因">
        <CountedTextArea
          id="modify-reason"
          label="變更原因（必填）"
          value={reason}
          onChange={setReason}
          max={200}
          testId="order-modify-input-reason"
        />
      </SectionCard>

      {isSitter && (
        <SectionCard title="報價">
          <AdjustmentFields
            detail={detail}
            surcharge={adj.surcharge}
            discount={adj.discount}
            reason={adj.reason}
            onChange={(p) => setAdj({ ...adj, ...p })}
          />
          <KV label="系統試算新總額">{formatMoney(systemAmount)}</KV>
          <KV label="新總額">{formatMoney(newTotal)}</KV>
          {detail.netPaid !== undefined && (
            <KV label="差額">{diffText(newTotal - detail.netPaid)}</KV>
          )}
          <p className="muted" style={{ margin: 0 }}>
            金額以送出後系統計算為準
          </p>
          {newTotal < 0 && <p className="field-error">新總額不可小於 0</p>}
        </SectionCard>
      )}

      <ErrorText error={action.error} context="modification" />
      <div className="btn-row">
        <Button variant="secondary" onClick={() => navigate(backHref)}>
          返回
        </Button>
        <Button
          className="btn-primary"
          disabled={!valid || action.pending}
          onClick={submit}
          data-testid="order-modify-btn-submit"
        >
          {action.pending ? '送出中…' : '送出'}
        </Button>
      </div>
    </PageContainer>
  );
};

export default OrderModificationWizard;
