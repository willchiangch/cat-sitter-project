import { describe, expect, it } from 'vitest';
import {
  PASSWORD_MAX_BYTES,
  PASSWORD_MAX_LENGTH,
  PASSWORD_MIN_LENGTH,
  checkPassword,
  passwordViolationFromPayload
} from './passwordRules';

// SD-FRONTEND-SPEC §2.8（NFR-003 SEC-AUTH-11）：8–64 code points 且 UTF-8 ≤ 72 bytes，不強制字元類型
describe('checkPassword', () => {
  it('常數與後端一致', () => {
    expect([PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH, PASSWORD_MAX_BYTES]).toEqual([8, 64, 72]);
  });

  it('7 字元不足、8 字元通過', () => {
    expect(checkPassword('a'.repeat(7))).toEqual({ lengthOk: false, bytesOk: true, ok: false });
    expect(checkPassword('a'.repeat(8))).toEqual({ lengthOk: true, bytesOk: true, ok: true });
  });

  it('64 字元通過、65 字元超過', () => {
    expect(checkPassword('a'.repeat(64)).ok).toBe(true);
    expect(checkPassword('a'.repeat(65)).lengthOk).toBe(false);
  });

  it('不強制字元類型：純數字、純小寫皆可', () => {
    expect(checkPassword('12345678').ok).toBe(true);
    expect(checkPassword('abcdefgh').ok).toBe(true);
  });

  it('以 code point 計長度：emoji（surrogate pair）算 1 字', () => {
    // 8 個 😀 = 8 code points（16 UTF-16 code units）、32 bytes
    const pwd = '😀'.repeat(8);
    expect(pwd.length).toBe(16);
    expect(checkPassword(pwd)).toEqual({ lengthOk: true, bytesOk: true, ok: true });
    // 7 個 emoji 不足 8 字，即使 UTF-16 長度 14
    expect(checkPassword('😀'.repeat(7)).lengthOk).toBe(false);
  });

  it('UTF-8 位元組上限：中文 3 bytes/字，24 字 = 72 bytes 通過，25 字 = 75 bytes 不通過', () => {
    expect(checkPassword('密'.repeat(24))).toEqual({ lengthOk: true, bytesOk: true, ok: true });
    expect(checkPassword('密'.repeat(25))).toEqual({ lengthOk: true, bytesOk: false, ok: false });
  });

  it('ASCII 64 字（64 bytes）通過；emoji 19 個（76 bytes）長度可但 bytes 超過', () => {
    expect(checkPassword('a'.repeat(64)).bytesOk).toBe(true);
    expect(checkPassword('😀'.repeat(19))).toEqual({ lengthOk: true, bytesOk: false, ok: false });
  });
});

describe('passwordViolationFromPayload', () => {
  it('非 password 欄位或無 payload 回 null', () => {
    expect(passwordViolationFromPayload(null)).toBeNull();
    expect(passwordViolationFromPayload(undefined)).toBeNull();
    expect(passwordViolationFromPayload({ field: 'email' })).toBeNull();
  });

  it('依 payload 鍵判斷哪條規則不符', () => {
    expect(passwordViolationFromPayload({ field: 'password', minLength: 8 })).toEqual({
      length: true,
      bytes: false
    });
    expect(passwordViolationFromPayload({ field: 'password', maxLength: 64 })).toEqual({
      length: true,
      bytes: false
    });
    expect(passwordViolationFromPayload({ field: 'password', maxBytes: 72 })).toEqual({
      length: false,
      bytes: true
    });
    expect(
      passwordViolationFromPayload({ field: 'password', minLength: 8, maxLength: 64, maxBytes: 72 })
    ).toEqual({ length: true, bytes: true });
  });

  it('field=password 但沒有任何規則鍵：視為長度不符', () => {
    expect(passwordViolationFromPayload({ field: 'password' })).toEqual({
      length: true,
      bytes: false
    });
  });
});
