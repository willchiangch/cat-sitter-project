import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import Button from '../../components/ui/Button';
import ModificationEditor from '../../components/orders/ModificationEditor';
import AdjustmentFields from '../../components/orders/AdjustmentFields';
import ModificationPreviewAmounts from '../../components/orders/ModificationPreviewAmounts';
import {
  adjustmentBody,
  isDraftValid,
  type AdjustmentDraft,
  type ModificationDraft
} from '../../components/orders/modificationDraft';
import {
  CountedTextArea,
  ErrorText,
  LoadingBlock,
  PageContainer,
  QueryErrorBlock,
  SectionCard
} from '../../components/orders/common';
import { useInvalidateOrder, useOrderDetailQuery } from '../../hooks/useOrders';
import { useIdempotentAction } from '../../hooks/useIdempotentAction';
import { useLatestDebounced } from '../../hooks/useLatestDebounced';
import { initiateModification, previewModification } from '../../api/orderApi';
import type { PreviewModificationRequest } from '../../api/orderApi';
import { isValidRequiredText, stripText } from '../../utils/text';

/**
 * 變更精靈（SD-ORDER §9.1 `/orders/:orderId/modify`，E2 + E32 試算 + E17；PRD-016 §4.1 / §4.2）。
 * 飼主送出 → REQUESTED；保母送出（同時報價）→ QUOTED。成功後進協商頁（金額以 E19 / E17 寫入值為準）。
 * 金額一律取 E32 試算（SD-ORDER §9.2）：減少目標、新增項目、加減金額變動後防抖 400 ms；
 * OWNER 的原因不送也不觸發（CQ-51），SITTER 的原因 / 調整原因會送並觸發。
 */
const OrderModificationWizard: React.FC<{ orderId: string }> = ({ orderId }) => {
  const navigate = useNavigate();
  const { data: detail, isLoading, error, refetch } = useOrderDetailQuery(orderId);
  const invalidate = useInvalidateOrder();
  const [type, setType] = useState<'CHANGE' | 'CANCEL_ALL'>('CHANGE');
  const [draft, setDraft] = useState<ModificationDraft>({ removeVisitIds: [], addItems: [] });
  const [reason, setReason] = useState('');
  const [adj, setAdj] = useState<AdjustmentDraft>({ surcharge: '', discount: '', reason: '' });
  const action = useIdempotentAction();

  const isSitter = detail?.role === 'SITTER';
  const canModify = (detail?.allowedActions ?? []).includes('REQUEST_MODIFICATION');
  const adjBody = adjustmentBody(
    adj,
    isSitter && detail?.modificationPricingMode === 'CUSTOM_ALLOWED'
  );
  const hasAdj = Object.keys(adjBody).length > 0;
  const contentBody: Omit<PreviewModificationRequest, 'reason'> = {
    type,
    ...(type === 'CHANGE'
      ? { removeVisitIds: draft.removeVisitIds, addItems: draft.addItems }
      : {}),
    ...adjBody
  };
  const contentReady = type === 'CANCEL_ALL' || isDraftValid(draft);
  const previewInput: PreviewModificationRequest | null =
    canModify && contentReady
      ? isSitter
        ? { ...contentBody, reason: stripText(reason) }
        : contentBody
      : null;
  const preview = useLatestDebounced(previewInput, (body, signal) =>
    previewModification(orderId, body, signal)
  );

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

  const backHref = `/${isSitter ? 'sitter' : 'owner'}/orders/${orderId}`;
  if (!canModify) {
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

  const valid =
    isValidRequiredText(reason, 200) &&
    contentReady &&
    (!hasAdj || isValidRequiredText(adj.reason, 200)) &&
    preview.status === 'success';

  const submit = async () => {
    const res = await action.run((key) =>
      initiateModification(orderId, { ...contentBody, reason: stripText(reason) }, key)
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
        </SectionCard>
      )}

      {preview.status !== 'idle' && (
        <SectionCard title="金額">
          <ModificationPreviewAmounts
            state={preview}
            role={isSitter ? 'SITTER' : 'OWNER'}
            testIdPrefix="order-modify-preview"
          />
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
