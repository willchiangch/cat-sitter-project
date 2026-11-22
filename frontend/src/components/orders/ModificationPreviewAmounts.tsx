import React from 'react';
import type { ModificationPreview } from '../../api/orderApi';
import type { LatestState } from '../../utils/latestDebounced';
import { formatMoney } from '../../utils/money';
import { ErrorText, KV } from './common';
import { diffText } from './modificationDraft';

/**
 * 變更金額試算結果（E32 / E33，SD-ORDER §9.2、§9.3）：金額全部取自伺服器試算。
 * 試算中顯示「計算中…」；試算失敗隱藏數字、顯示與送出相同的錯誤文案。
 * OWNER（CQ-50）：只有系統計價，標「預估」並註明以保母報價為準；SITTER 不加「預估」。
 */
const ModificationPreviewAmounts: React.FC<{
  state: LatestState<ModificationPreview>;
  role: 'OWNER' | 'SITTER';
  testIdPrefix: 'order-modify-preview' | 'modification-quote-preview';
}> = ({ state, role, testIdPrefix }) => {
  if (state.status === 'idle') return null;
  if (state.status === 'loading')
    return (
      <p
        role="status"
        className="muted"
        style={{ margin: 0 }}
        data-testid={`${testIdPrefix}-loading`}
      >
        計算中…
      </p>
    );
  if (state.status === 'error')
    return (
      <ErrorText error={state.error} context="modification" testId={`${testIdPrefix}-error`} />
    );

  const p = state.data;
  if (role === 'OWNER')
    return (
      <div className="stack" style={{ gap: '0.5rem' }}>
        <KV label="預估新總額">
          <span data-testid={`${testIdPrefix}-new-total`}>{formatMoney(p.newTotalAmount)}</span>
          （以保母報價為準）
        </KV>
        <KV label="預估差額" testId={`${testIdPrefix}-diff`}>
          {diffText(p.diff)}
        </KV>
      </div>
    );
  return (
    <div className="stack" style={{ gap: '0.5rem' }}>
      <KV label="系統計算新總額" testId={`${testIdPrefix}-system-amount`}>
        {formatMoney(p.systemAmount)}
      </KV>
      <KV label="新總額" testId={`${testIdPrefix}-new-total`}>
        {formatMoney(p.newTotalAmount)}
      </KV>
      <KV label="差額" testId={`${testIdPrefix}-diff`}>
        {diffText(p.diff)}
      </KV>
    </div>
  );
};

export default ModificationPreviewAmounts;
