/**
 * 站內導頁（開放重導防護，SD-FRONTEND-SPEC §2.5，NFR-003 SEC-WEB-5）。
 * 由網址參數（`/login?redirect=`、`/register?redirect=`）或 API 回應（通知 linkUrl）決定的導頁目標一律經此判斷：
 * - 須以 `/` 開頭，第二字元不是 `/` 也不是 `\`（瀏覽器把 `\` 視為 `/`，`/\evil.example` 是協定相對網址）
 * - 不含控制字元（U+0000–U+001F、U+007F）與空白
 * - `new URL(target, location.origin).origin === location.origin`
 * 輸入值以 `URLSearchParams.get` 取得（已解碼一次），之後不再自行 decodeURIComponent。全站不得另寫導頁判斷。
 */
// eslint-disable-next-line no-control-regex
const FORBIDDEN_CHARS = /[\u0000-\u001f\u007f\s]/u;

export const isSafeRedirect = (raw: string | null | undefined): raw is string => {
  if (!raw || typeof raw !== 'string') return false;
  if (raw[0] !== '/') return false;
  if (raw[1] === '/' || raw[1] === '\\') return false;
  if (FORBIDDEN_CHARS.test(raw)) return false;
  try {
    const origin = window.location.origin;
    return new URL(raw, origin).origin === origin;
  } catch {
    return false;
  }
};

export const getSafeRedirectTarget = (raw: string | null | undefined, fallback: string): string =>
  isSafeRedirect(raw) ? raw : fallback;
