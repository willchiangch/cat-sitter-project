/**
 * 密碼規則（SD-FRONTEND-SPEC §2.8，NFR-003 SEC-AUTH-11）：長度 8–64 個字元（以 code point 計）且
 * UTF-8 ≤ 72 bytes（BCrypt 上限），與後端同一規則；不強制字元類型。註冊、重設密碼、改密碼共用。
 */
export const PASSWORD_MIN_LENGTH = 8;
export const PASSWORD_MAX_LENGTH = 64;
export const PASSWORD_MAX_BYTES = 72;

export interface PasswordCheck {
  lengthOk: boolean;
  bytesOk: boolean;
  ok: boolean;
}

export const checkPassword = (pwd: string): PasswordCheck => {
  const len = [...pwd].length;
  const bytes = new TextEncoder().encode(pwd).length;
  const lengthOk = len >= PASSWORD_MIN_LENGTH && len <= PASSWORD_MAX_LENGTH;
  const bytesOk = bytes <= PASSWORD_MAX_BYTES;
  return { lengthOk, bytesOk, ok: lengthOk && bytesOk };
};

/** 後端 400 VALIDATION_FAILED（field=password）payload → 哪一條規則不符 */
export const passwordViolationFromPayload = (
  payload: Record<string, unknown> | null | undefined
): { length: boolean; bytes: boolean } | null => {
  if (!payload || payload.field !== 'password') return null;
  const bytes = 'maxBytes' in payload;
  const length = 'minLength' in payload || 'maxLength' in payload;
  return { length: length || !bytes, bytes };
};
