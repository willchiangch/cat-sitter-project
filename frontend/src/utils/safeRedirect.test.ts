import { describe, expect, it } from 'vitest';
import { getSafeRedirectTarget, isSafeRedirect, withSafeRedirect } from './safeRedirect';

// SD-FRONTEND-SPEC §2.5（NFR-003 SEC-WEB-5）
const FALLBACK = '/demo';

describe('safeRedirect', () => {
  it.each(['/sitter/orders?tab=x', '/booking/a1b2c3', '/owner/orders/123#top', '/'])(
    '站內路徑 %s 照常導回',
    (target) => {
      expect(isSafeRedirect(target)).toBe(true);
      expect(getSafeRedirectTarget(target, FALLBACK)).toBe(target);
    }
  );

  it.each([
    ['//evil.example', '協定相對網址'],
    ['/\\evil.example', '反斜線（瀏覽器視為 /）'],
    ['https://evil.example', '絕對網址'],
    ['javascript:alert(1)', 'javascript: scheme'],
    ['%2F%2Fevil.example', '未解碼的 //'],
    ['/\t/evil.example', 'Tab 控制字元'],
    ['/\n/evil.example', '換行控制字元'],
    ['/\u007f', 'DEL'],
    ['/a b', '空白'],
    ['', '空字串'],
    ['evil.example', '不以 / 開頭']
  ])('擋下 %s（%s）', (target) => {
    expect(isSafeRedirect(target)).toBe(false);
    expect(getSafeRedirectTarget(target, FALLBACK)).toBe(FALLBACK);
  });

  it('URLSearchParams 解碼後的 %2F%2F 變成 // 而被擋', () => {
    const raw = new URLSearchParams('redirect=%2F%2Fevil.example').get('redirect');
    expect(raw).toBe('//evil.example');
    expect(getSafeRedirectTarget(raw, FALLBACK)).toBe(FALLBACK);
  });

  it('URLSearchParams 解碼後的 %09 變成 Tab 而被擋', () => {
    const raw = new URLSearchParams('redirect=/%09/evil.example').get('redirect');
    expect(raw).toBe('/\t/evil.example');
    expect(getSafeRedirectTarget(raw, FALLBACK)).toBe(FALLBACK);
  });

  it('null / undefined 回 fallback', () => {
    expect(getSafeRedirectTarget(null, FALLBACK)).toBe(FALLBACK);
    expect(getSafeRedirectTarget(undefined, FALLBACK)).toBe(FALLBACK);
  });

  it('解析後 origin 一律與站台相同', () => {
    for (const t of ['/a', '/sitter/orders?tab=x']) {
      expect(new URL(getSafeRedirectTarget(t, FALLBACK), window.location.origin).origin).toBe(
        window.location.origin
      );
    }
  });
});

describe('withSafeRedirect（登入 ⇄ 註冊互連帶 redirect）', () => {
  it('安全目標重新編碼後帶上，往返解碼一致', () => {
    const target = '/booking/abc?x=1&y=2';
    const href = withSafeRedirect('/register', target);
    expect(href).toBe(`/register?redirect=${encodeURIComponent(target)}`);
    expect(new URLSearchParams(href.split('?')[1]).get('redirect')).toBe(target);
  });

  it.each([null, undefined, '', '//evil.example', '/\\evil.example', 'https://evil.example'])(
    '不安全或缺值 %s 不帶參數',
    (raw) => {
      expect(withSafeRedirect('/register', raw)).toBe('/register');
    }
  );
});
