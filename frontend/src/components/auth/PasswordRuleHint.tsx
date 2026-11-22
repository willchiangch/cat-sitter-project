import React from 'react';
import { SUCCESS_TEXT_COLOR } from '../ui/statusTone';
import { checkPassword } from '../../utils/passwordRules';

/**
 * 密碼規則即時提示（SD-FRONTEND-SPEC §2.8）：兩條規則各自顯示達成狀態；後端回 400 VALIDATION_FAILED
 * （field=password）時以 `serverViolation` 標示對應規則，不顯示後端原始訊息。
 */
const PasswordRuleHint: React.FC<{
  password: string;
  serverViolation?: { length: boolean; bytes: boolean } | null;
}> = ({ password, serverViolation }) => {
  const check = checkPassword(password);
  const rules = [
    {
      id: 'auth-password-rule-length',
      text: '8–64 個字元',
      ok: check.lengthOk && !serverViolation?.length
    },
    {
      id: 'auth-password-rule-bytes',
      text: '長度上限以位元組計（全中文約 24 字）',
      ok: check.bytesOk && !serverViolation?.bytes
    }
  ];
  return (
    <ul
      style={{ margin: 0, paddingLeft: 0, listStyle: 'none', fontSize: '0.8125rem' }}
      aria-live="polite"
    >
      {rules.map((r) => (
        <li
          key={r.id}
          data-testid={r.id}
          data-ok={r.ok}
          style={{ color: r.ok ? SUCCESS_TEXT_COLOR : 'var(--color-on-surface-variant)' }}
        >
          {r.ok ? '✓' : '○'} {r.text}
        </li>
      ))}
    </ul>
  );
};

export default PasswordRuleHint;
