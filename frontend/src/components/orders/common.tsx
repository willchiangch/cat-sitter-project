import React from 'react';
import Button from '../ui/Button';
import BottomSheet from '../modals/BottomSheet';
import { PAGE_PADDING } from '../layout/pageLayout';
import { getApiError } from '../../api/apiError';
import { errorMessage, type ErrorContext } from '../../api/errorMessages';
import { codePointLength, truncateCodePoints } from '../../utils/text';

/** 頁面最外層容器（PAGE_PADDING，SD-FRONTEND-SPEC §2.4 N17） */
export const PageContainer: React.FC<{ children: React.ReactNode; testId?: string }> = ({
  children,
  testId
}) => (
  <div data-testid={testId} style={{ padding: PAGE_PADDING }} className="stack">
    {children}
  </div>
);

export const SectionCard: React.FC<{
  title?: React.ReactNode;
  children: React.ReactNode;
  testId?: string;
}> = ({ title, children, testId }) => (
  <section className="card-layered stack" data-testid={testId} style={{ padding: '1.25rem' }}>
    {title && <h3 className="section-title">{title}</h3>}
    {children}
  </section>
);

export const LoadingBlock: React.FC<{ text?: string }> = ({ text = '載入中…' }) => (
  <div role="status" className="muted" style={{ padding: '1rem 0', textAlign: 'center' }}>
    {text}
  </div>
);

/** 錯誤區塊：依錯誤碼決定文案（無權限 / 找不到 / 其他）並提供重試 */
export const QueryErrorBlock: React.FC<{
  error: unknown;
  context?: ErrorContext;
  onRetry?: () => void;
  testId?: string;
}> = ({ error, context = 'generic', onRetry, testId }) => {
  const err = getApiError(error);
  const canRetry =
    err.code !== 'FORBIDDEN' && err.code !== 'NOT_FOUND' && err.code !== 'ROLE_MISMATCH';
  return (
    <div role="alert" data-testid={testId} className="surface-block stack">
      <p style={{ margin: 0, color: 'var(--color-error)', fontWeight: 600 }}>
        {errorMessage(err, context)}
      </p>
      {canRetry && onRetry && (
        <div className="btn-row">
          <Button variant="secondary" onClick={onRetry}>
            重試
          </Button>
        </div>
      )}
    </div>
  );
};

/** 送出後的錯誤文字 */
export const ErrorText: React.FC<{
  error: unknown;
  context: ErrorContext;
  testId?: string;
}> = ({ error, context, testId }) => {
  if (!error) return null;
  return (
    <p role="alert" className="field-error" data-testid={testId}>
      {typeof error === 'string' ? error : errorMessage(getApiError(error), context)}
    </p>
  );
};

/** 以 code point 計數、onChange 截斷的文字欄（SD-FULFILL §9.3，不使用 maxLength） */
export const CountedTextArea: React.FC<{
  id: string;
  label: string;
  value: string;
  onChange: (v: string) => void;
  max: number;
  testId?: string;
  counterTestId?: string;
  placeholder?: string;
  rows?: number;
  disabled?: boolean;
  showRemaining?: boolean;
}> = ({
  id,
  label,
  value,
  onChange,
  max,
  testId,
  counterTestId,
  placeholder,
  rows = 3,
  disabled,
  showRemaining
}) => {
  const len = codePointLength(value);
  return (
    <div>
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      <textarea
        id={id}
        className="form-input"
        rows={rows}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        data-testid={testId}
        onChange={(e) => onChange(truncateCodePoints(e.target.value, max))}
      />
      <div
        className="muted"
        style={{ textAlign: 'right', fontSize: '0.75rem' }}
        data-testid={counterTestId}
      >
        {showRemaining ? `${max - len}` : `${len} / ${max}`}
      </div>
    </div>
  );
};

/** 確認對話框（BottomSheet） */
export const ConfirmSheet: React.FC<{
  isOpen: boolean;
  title: string;
  message: React.ReactNode;
  confirmText: string;
  onConfirm: () => void;
  onCancel: () => void;
  pending?: boolean;
  confirmDisabled?: boolean;
  testId?: string;
  confirmTestId?: string;
  cancelTestId?: string;
  danger?: boolean;
}> = ({
  isOpen,
  title,
  message,
  confirmText,
  onConfirm,
  onCancel,
  pending,
  confirmDisabled,
  testId,
  confirmTestId,
  cancelTestId,
  danger
}) => (
  <BottomSheet isOpen={isOpen} onClose={onCancel} title={title}>
    <div className="stack" data-testid={testId}>
      <div style={{ color: 'var(--color-on-surface)' }}>{message}</div>
      <div className="btn-row">
        <Button variant="secondary" onClick={onCancel} data-testid={cancelTestId}>
          取消
        </Button>
        <Button
          className={danger ? undefined : 'btn-primary'}
          variant={danger ? 'danger-outline' : 'primary'}
          onClick={onConfirm}
          disabled={pending || confirmDisabled}
          data-testid={confirmTestId}
        >
          {pending ? '處理中…' : confirmText}
        </Button>
      </div>
    </div>
  </BottomSheet>
);

export const KV: React.FC<{
  label: React.ReactNode;
  children: React.ReactNode;
  testId?: string;
}> = ({ label, children, testId }) => (
  <div className="kv-row">
    <span>{label}</span>
    <span data-testid={testId}>{children}</span>
  </div>
);
