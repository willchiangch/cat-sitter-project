import React, { useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import Button from '../ui/Button';
import StatusChip from '../ui/StatusChip';
import { subStatusTone } from '../ui/statusTone';
import { paymentPurposeLabel, paymentStatusLabel, submissionStatusLabel } from '../ui/statusLabels';
import { formatMoney } from '../../utils/money';
import { formatDateTime, isPast } from '../../utils/datetime';
import { isValidRequiredText } from '../../utils/text';
import {
  adminVoidPayment,
  getProofUrl,
  rejectPayment,
  submitPaymentProof,
  verifyPayment
} from '../../api/orderApi';
import type { PaymentView, SubmissionItem } from '../../api/orderApi';
import { newIdempotencyKey } from '../../api/idempotency';
import { getApiError } from '../../api/apiError';
import { errorMessage } from '../../api/errorMessages';
import { useInvalidateOrder, usePaymentsQuery, useSubmissionsQuery } from '../../hooks/useOrders';
import { CountedTextArea, ConfirmSheet, ErrorText, LoadingBlock, QueryErrorBlock } from './common';
import { extensionOf, ImageDecodeError, reencodeImage } from '../../utils/imageReencode';

const DISCLAIMER = '我瞭解平台不代管此筆線下款項，此筆交易風險由飼主與保母雙方自行承擔';
const PROOF_MAX_BYTES = 5 * 1024 * 1024;
const PROOF_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

/** 憑證圖片：點擊才向 E13 取 5 分鐘簽名網址（不快取），只把後端給的簽名網址放進 img（§2.6 SEC-WEB-6） */
const ProofImage: React.FC<{ orderId: string; paymentId: string; submissionId: string }> = ({
  orderId,
  paymentId,
  submissionId
}) => {
  const [url, setUrl] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(false);
  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      setUrl((await getProofUrl(orderId, paymentId, submissionId)).url);
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  };
  return (
    <div data-testid={`submission-history-image-${submissionId}`}>
      {url ? (
        <img
          src={url}
          alt="付款憑證"
          onError={() => setUrl(null)}
          style={{ maxWidth: '100%', borderRadius: 'var(--radius-sm)' }}
        />
      ) : (
        <Button
          variant="secondary"
          onClick={load}
          disabled={loading}
          style={{ padding: '0.5rem 1rem' }}
        >
          {loading ? '載入中…' : '檢視憑證'}
        </Button>
      )}
      <ErrorText error={error} context="payment" />
    </div>
  );
};

const SubmissionHistory: React.FC<{ orderId: string; payment: PaymentView }> = ({
  orderId,
  payment
}) => {
  const { data, isLoading, error, refetch } = useSubmissionsQuery(orderId, payment.paymentId);
  return (
    <div data-testid="submission-history" className="stack" style={{ gap: '0.5rem' }}>
      <strong style={{ fontSize: '0.875rem' }}>憑證紀錄</strong>
      {isLoading ? (
        <LoadingBlock />
      ) : error ? (
        <QueryErrorBlock error={error} context="payment" onRetry={() => refetch()} />
      ) : !data || data.length === 0 ? (
        <p className="muted" style={{ margin: 0 }}>
          尚未上傳憑證
        </p>
      ) : (
        data.map((s: SubmissionItem) => (
          <div
            key={s.submissionId}
            className="surface-block stack"
            style={{ gap: '0.375rem' }}
            data-testid={`submission-history-item-${s.submissionId}`}
          >
            <div className="btn-row" style={{ alignItems: 'center' }}>
              <StatusChip
                label={s.voided ? '已作廢' : submissionStatusLabel(s.status)}
                tone={s.voided ? 'neutral' : subStatusTone(s.status)}
                status={s.status}
              />
              <span className="muted">後五碼 {s.lastFive}</span>
            </div>
            <span className="muted">上傳時間 {formatDateTime(s.submittedAt)}</span>
            {s.status === 'REJECTED' && s.rejectReason && (
              <span style={{ color: 'var(--color-error)', overflowWrap: 'anywhere' }}>
                已駁回：{s.rejectReason}
              </span>
            )}
            <ProofImage
              orderId={orderId}
              paymentId={payment.paymentId}
              submissionId={s.submissionId}
            />
          </div>
        ))
      )}
    </div>
  );
};

const UploadForm: React.FC<{ orderId: string; payment: PaymentView; onDone: () => void }> = ({
  orderId,
  payment,
  onDone
}) => {
  const [agreed, setAgreed] = useState(false);
  const [lastFive, setLastFive] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [pending, setPending] = useState(false);
  const keyRef = useRef<string | null>(null);
  const fieldsSig = useRef('');

  const lastFiveOk = /^[0-9]{5}$/.test(lastFive);
  const canSubmit = agreed && lastFiveOk && !!file && !pending;

  const submit = async () => {
    if (!file) return;
    setError(null);
    if (!PROOF_TYPES.includes(file.type)) {
      setError('僅支援 JPG、PNG、WebP');
      return;
    }
    setPending(true);
    try {
      // §2.7：重新編碼移除 EXIF，維持原格式
      const blob = await reencodeImage(file);
      if (blob.size > PROOF_MAX_BYTES) {
        setError('憑證圖片需小於 5MB');
        return;
      }
      const sig = `${lastFive}|${file.name}|${file.size}|${file.lastModified}`;
      if (!keyRef.current || fieldsSig.current !== sig) {
        keyRef.current = newIdempotencyKey();
        fieldsSig.current = sig;
      }
      await submitPaymentProof(
        orderId,
        payment.paymentId,
        { lastFive, file: blob, fileName: `proof.${extensionOf(blob.type)}` },
        keyRef.current
      );
      keyRef.current = null;
      onDone();
    } catch (e) {
      if (e instanceof ImageDecodeError) setError('僅支援 JPG、PNG、WebP');
      else setError(e);
    } finally {
      setPending(false);
    }
  };

  const id = `proof-${payment.paymentId}`;
  return (
    <div className="stack">
      <label
        style={{ display: 'flex', gap: '0.5rem', alignItems: 'flex-start', fontSize: '0.875rem' }}
      >
        <input
          type="checkbox"
          checked={agreed}
          onChange={(e) => setAgreed(e.target.checked)}
          data-testid="proof-disclaimer-checkbox"
          style={{ marginTop: '0.2rem', flexShrink: 0 }}
        />
        <span style={{ minWidth: 0 }}>{DISCLAIMER}</span>
      </label>
      <div>
        <label className="field-label" htmlFor={`${id}-last5`}>
          您匯出帳戶的後五碼
        </label>
        <input
          id={`${id}-last5`}
          className="form-input"
          inputMode="numeric"
          value={lastFive}
          onChange={(e) => setLastFive(e.target.value.replace(/\D/g, '').slice(0, 5))}
          data-testid="proof-last5-input"
        />
        {lastFive && !lastFiveOk && <p className="field-error">請輸入 5 位數字</p>}
      </div>
      <div>
        <label className="field-label" htmlFor={`${id}-file`}>
          匯款憑證（JPG、PNG、WebP，小於 5MB）
        </label>
        <input
          id={`${id}-file`}
          type="file"
          accept="image/jpeg,image/png,image/webp"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          data-testid="proof-file-input"
          style={{ maxWidth: '100%' }}
        />
      </div>
      <ErrorText error={error} context="payment" />
      <div className="btn-row">
        <Button
          className="btn-primary"
          disabled={!canSubmit}
          onClick={submit}
          data-testid="proof-submit-btn"
        >
          {pending ? '上傳中…' : '送出憑證'}
        </Button>
      </div>
    </div>
  );
};

const PaymentBlock: React.FC<{
  orderId: string;
  payment: PaymentView;
  serverTime: string;
  refundDisputeHref?: string;
  adminCanVoid?: boolean;
}> = ({ orderId, payment, serverTime, refundDisputeHref, adminCanVoid }) => {
  const invalidate = useInvalidateOrder();
  const [mode, setMode] = useState<'none' | 'reject' | 'void'>('none');
  const [overdueOpen, setOverdueOpen] = useState(false);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  const [pending, setPending] = useState(false);
  const keyRef = useRef<string | null>(null);
  const actions = payment.allowedActions ?? [];
  const refresh = () => invalidate(orderId);

  const run = async (fn: (key: string) => Promise<unknown>) => {
    setError(null);
    setPending(true);
    keyRef.current = keyRef.current ?? newIdempotencyKey();
    try {
      await fn(keyRef.current);
      keyRef.current = null;
      setMode('none');
      setReason('');
      await refresh();
    } catch (e) {
      // 確定性錯誤（409 / 422 已被冪等記錄）→ 使用者修改後重送須換 key
      if (getApiError(e).status !== null) keyRef.current = null;
      setError(e);
    } finally {
      setPending(false);
    }
  };

  const verify = () =>
    payment.currentSubmissionId &&
    run((key) => verifyPayment(orderId, payment.paymentId, payment.currentSubmissionId!, key));

  const startReject = () => {
    // PRD-007 BR-22：已過 due_at 先跳確認框
    if (payment.dueAt && isPast(payment.dueAt, serverTime)) {
      setOverdueOpen(true);
    } else {
      setMode('reject');
    }
  };

  const overdueText =
    payment.purpose === 'SUPPLEMENT'
      ? '已過補款期限，駁回後新增的行程將不會生效（已減少的行程維持取消）'
      : '已過付款期限，駁回後訂單將被取消';

  return (
    <div
      className="surface-block stack"
      data-testid={`payment-block-${payment.paymentId}`}
      style={{ background: 'var(--color-surface-lowest)' }}
    >
      <div className="btn-row" style={{ alignItems: 'center', justifyContent: 'space-between' }}>
        <strong>{paymentPurposeLabel(payment.purpose)}</strong>
        <StatusChip
          label={paymentStatusLabel(payment.status)}
          tone={subStatusTone(payment.status)}
          status={payment.status}
          testId="payment-status"
        />
      </div>
      <div style={{ fontSize: '1.5rem', fontWeight: 700 }} data-testid="payment-amount">
        {formatMoney(payment.amount)}
      </div>
      {payment.dueAt && payment.status === 'UNPAID' && payment.direction === 'CHARGE' && (
        <p className="muted" style={{ margin: 0 }} data-testid="payment-due-at">
          請於 {formatDateTime(payment.dueAt)} 前完成付款
        </p>
      )}
      {payment.direction === 'CHARGE' && payment.accountSnapshot && (
        <div className="stack" style={{ gap: '0.25rem' }} data-testid="payment-account-snapshot">
          <span className="muted">收款帳號</span>
          <span style={{ overflowWrap: 'anywhere' }}>
            {payment.accountSnapshot.bankCode} {payment.accountSnapshot.branch}
          </span>
          <span style={{ overflowWrap: 'anywhere', fontWeight: 600 }}>
            {payment.accountSnapshot.accountNumber}
          </span>
          <span style={{ overflowWrap: 'anywhere' }}>{payment.accountSnapshot.accountName}</span>
        </div>
      )}
      {payment.direction === 'REFUND' && (
        <p className="muted" style={{ margin: 0 }}>
          退款帳號請與飼主自行聯繫
        </p>
      )}

      {actions.includes('UPLOAD') && (
        <UploadForm orderId={orderId} payment={payment} onDone={refresh} />
      )}

      <SubmissionHistory orderId={orderId} payment={payment} />

      {(actions.includes('VERIFY') || actions.includes('REJECT')) && (
        <div className="btn-row">
          {actions.includes('VERIFY') && (
            <Button
              className="btn-primary"
              onClick={verify}
              disabled={pending || !payment.currentSubmissionId}
              data-testid="btn-verify-payment"
            >
              {payment.direction === 'REFUND' ? '確認已收到退款' : '確認收款'}
            </Button>
          )}
          {actions.includes('REJECT') && mode !== 'reject' && (
            <Button
              variant="danger-outline"
              onClick={startReject}
              disabled={pending}
              data-testid="btn-reject-payment"
            >
              駁回憑證
            </Button>
          )}
        </div>
      )}

      {payment.direction === 'REFUND' && actions.includes('VERIFY') && refundDisputeHref && (
        <Link
          to={refundDisputeHref}
          data-testid="payment-refund-dispute-link"
          style={{ color: 'var(--color-primary)' }}
        >
          如有異議，請申報款項爭議
        </Link>
      )}

      {mode === 'reject' && (
        <div className="stack">
          <CountedTextArea
            id={`reject-${payment.paymentId}`}
            label="駁回原因"
            value={reason}
            onChange={setReason}
            max={200}
            testId="reject-reason-input"
          />
          <div className="btn-row">
            <Button variant="secondary" onClick={() => setMode('none')}>
              取消
            </Button>
            <Button
              variant="danger-outline"
              disabled={
                pending || !isValidRequiredText(reason, 200) || !payment.currentSubmissionId
              }
              onClick={() =>
                run((key) =>
                  rejectPayment(
                    orderId,
                    payment.paymentId,
                    { submissionId: payment.currentSubmissionId!, reason },
                    key
                  )
                )
              }
              data-testid="reject-submit-btn"
            >
              {pending ? '處理中…' : '確定駁回'}
            </Button>
          </div>
        </div>
      )}

      {adminCanVoid && payment.purpose === 'INITIAL' && payment.status === 'SUBMITTED' && (
        <div className="stack">
          {mode !== 'void' ? (
            <div className="btn-row">
              <Button
                variant="danger-outline"
                onClick={() => setMode('void')}
                data-testid="btn-admin-void"
              >
                作廢付款並取消訂單
              </Button>
            </div>
          ) : (
            <>
              <CountedTextArea
                id={`void-${payment.paymentId}`}
                label="作廢原因"
                value={reason}
                onChange={setReason}
                max={200}
                testId="admin-void-input-reason"
              />
              <div className="btn-row">
                <Button variant="secondary" onClick={() => setMode('none')}>
                  取消
                </Button>
                <Button
                  variant="danger-outline"
                  disabled={pending || !isValidRequiredText(reason, 200)}
                  onClick={() =>
                    run((key) => adminVoidPayment(orderId, payment.paymentId, reason, key))
                  }
                  data-testid="admin-void-btn-confirm"
                >
                  {pending ? '處理中…' : '確定作廢'}
                </Button>
              </div>
            </>
          )}
        </div>
      )}

      {error ? (
        <p role="alert" className="field-error">
          {typeof error === 'string' ? error : errorMessage(getApiError(error), 'payment')}
        </p>
      ) : null}

      <ConfirmSheet
        isOpen={overdueOpen}
        title="確認駁回"
        message={overdueText}
        confirmText="確認"
        onCancel={() => setOverdueOpen(false)}
        onConfirm={() => {
          setOverdueOpen(false);
          setMode('reject');
        }}
        testId="reject-overdue-dialog"
        confirmTestId="reject-overdue-dialog-btn-confirm"
        cancelTestId="reject-overdue-dialog-btn-cancel"
        danger
      />
    </div>
  );
};

/**
 * 付款區塊（PRD-007、SD-ORDER §9.1）：只顯示 payment 的 amount（不顯示訂單總額，BR-1）。
 * 按鈕依 E10 `allowedActions`；E2 沒有 payment 時不渲染（AC-1.1）。
 */
const PaymentSection: React.FC<{
  orderId: string;
  serverTime: string;
  filter?: (p: PaymentView) => boolean;
  refundDisputeHref?: string;
  adminCanVoid?: boolean;
  title?: string;
}> = ({ orderId, serverTime, filter, refundDisputeHref, adminCanVoid, title = '付款' }) => {
  const { data, isLoading, error, refetch } = usePaymentsQuery(orderId);
  if (isLoading) return <LoadingBlock />;
  if (error) return <QueryErrorBlock error={error} context="payment" onRetry={() => refetch()} />;
  const list = (data ?? []).filter(filter ?? (() => true));
  if (list.length === 0) return null;
  return (
    <section className="card-layered stack" style={{ padding: '1.25rem' }}>
      <h3 className="section-title">{title}</h3>
      {list.map((p) => (
        <PaymentBlock
          key={p.paymentId}
          orderId={orderId}
          payment={p}
          serverTime={serverTime}
          refundDisputeHref={refundDisputeHref}
          adminCanVoid={adminCanVoid}
        />
      ))}
    </section>
  );
};

export default PaymentSection;
